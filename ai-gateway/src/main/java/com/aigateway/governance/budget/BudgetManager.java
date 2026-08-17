package com.aigateway.governance.budget;

import com.aigateway.core.exception.GatewayException;
import com.aigateway.governance.model.Budget;
import com.aigateway.governance.model.BudgetPeriod;
import com.aigateway.governance.model.BudgetScope;
import com.aigateway.governance.model.LimitType;
import com.aigateway.governance.persistence.BudgetDao;
import com.aigateway.observability.GatewayMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 预算管理器：管「总量」的第二层治理
 *
 * 设计语义：
 * 1. 内存预算对象是热路径（预检 / 扣减 O(1)），DB 的 used_value 是慢路径对账快照
 *    （由 UsageLedger 的 flush 顺带落库，重启后从库恢复）；
 * 2. 扣减用「小锁 + 比较写」：锁粒度 = 单个预算对象（不是全局、不是整个请求），
 *    并发扣同一预算串行化，不同预算互不阻塞——这就是「并发下不超扣」的实现；
 * 3. 周期惰性重置：periodChanged 才重置，不跑定时任务扫表；
 * 4. 预警是软预算：跨过 warnRatios 阈值记日志 + 指标，不阻断请求；
 * 5. 超额动作（REJECT / DEGRADE）由调用方按 exceedAction 配置执行，
 *    本类只回报 ConsumeResult 与预检结果。
 *
 */
@Component
public class BudgetManager {

    private static final Logger log = LoggerFactory.getLogger(BudgetManager.class);

    /** 预算定位键：scope + scopeValue（同 scope 同 value 只允许一个限额定义） */
    private record BudgetKey(BudgetScope scope, String scopeValue) {}

    private final BudgetDao dao;
    private final List<Double> warnRatios;      // 如 [0.7, 0.9]
    private final GatewayMetrics metrics;
    private final Map<BudgetKey, Budget> budgets = new ConcurrentHashMap<>();
    private final Map<BudgetKey, Integer> warnedLevel = new ConcurrentHashMap<>(); // 已触发到第几档

    public BudgetManager(BudgetDao dao, List<Double> warnRatios, GatewayMetrics metrics) {
        this.dao = dao;
        this.warnRatios = warnRatios.stream().sorted().toList();
        this.metrics = metrics;
        loadFromDb();
    }

    /** 启动加载：DB 里的预算定义 + used 快照（丢掉的未对账窗口是已知取舍） */
    private void loadFromDb() {
        for (Budget b : dao.findAll()) {
            budgets.put(new BudgetKey(b.scope(), b.scopeValue()), b);
        }
        log.info("预算加载完成，共 {} 条", budgets.size());
    }

    /** 创建或替换一个预算定义（管理端点调用；同 key 覆盖） */
    public Budget upsert(BudgetScope scope, String scopeValue, LimitType limitType,
                         BudgetPeriod period, double limit) {
        if(limit <= 0){
            throw new GatewayException(400, "invalid_request", "预算上限必须 > 0");
        }
        Budget b = new Budget(scope, scopeValue, limitType, period, limit);
        budgets.put(new BudgetKey(scope, scopeValue), b);
        warnedLevel.put(new BudgetKey(scope, scopeValue), 0);
        dao.upsert(b);
        log.info("预算设置 scope={} value={} type={} period={} limit={}",
                scope, scopeValue, limitType, period, limit);
        return b;
    }

    /**
     * 预检：当前周期剩余是否 >= 本次预估用量（不改变任何状态）。
     * 两个量纲参数（tokenAmount / costAmount）由预算自身的 limitType 决定用哪个——
     * 调用方无脑传双值，本方法按预算配置换算，避免「令牌是 COST、预算是 TOKEN」
     * 时把美元当 token 数扣的量纲错乱。
     * @return true = 可放行
     */
    public boolean precheck(BudgetScope scope, String scopeValue,
                            long tokenAmount, double costAmount) {
        Budget b = budgets.get(new BudgetKey(scope, scopeValue));
        if(b == null) return true;
        synchronized(b){
            lazyResetIfNeeded(b);
            long unit = b.toUnit(b.limitType() == LimitType.TOKEN ? tokenAmount : costAmount);
            long limitUnit = b.toUnit(b.limit());
            return b.usedMicro().get() + unit <= limitUnit;
        }
    }

    /** 剩余比率（Scorer 的 budget_remaining_ratio 信号输入）；未配置预算返回 1.0 */
    public double remainingRatio(BudgetScope scope, String scopeValue) {
        Budget b = budgets.get(new BudgetKey(scope, scopeValue));
        if(b == null) return 1.0;
        synchronized(b){
            lazyResetIfNeeded(b);
            return b.remainingRatio();
        }
    }

    /**
     * 结算扣减：按实际用量原子扣减（只有成功响应才调用）。
     * 与 precheck 同量纲约定：tokenAmount / costAmount 双值传入，按预算 limitType 取用。
     * @return OK = 扣减成功；EXCEEDED = 超出上限（拒绝，调用方回 429 budget_exceeded）
     */
    public ConsumeResult consume(BudgetScope scope, String scopeValue,
                                 long tokenAmount, double costAmount) {
        Budget b = budgets.get(new BudgetKey(scope, scopeValue));
        if(b == null) return ConsumeResult.ok(1.0);
        synchronized(b){
            lazyResetIfNeeded(b);
            long unit = b.toUnit(b.limitType() == LimitType.TOKEN ? tokenAmount : costAmount);
            long limitUnit = b.toUnit(b.limit());
            long used = b.usedMicro().get();
            if(used + unit > limitUnit){
                return ConsumeResult.exceeded(b.remainingRatio());
            }
            b.usedMicro().set(used + unit);
        }
        checkWarnings(scope, scopeValue, b);
        return ConsumeResult.ok(b.remainingRatio());
    }

    /** 扣减结果：remainingRatio 供上层写信号/指标 */
    public record ConsumeResult(boolean ok, double remainingRatio) {
        static ConsumeResult ok(double ratio)      { return new ConsumeResult(true, ratio); }
        static ConsumeResult exceeded(double ratio) { return new ConsumeResult(false, ratio); }
    }

    /** 预警：跨过第 i 档阈值（70% / 90%）才记一次（避免每请求重复刷日志） */
    private void checkWarnings(BudgetScope scope, String scopeValue, Budget b) {
         double ratio = b.remainingRatio();
        double usedRatio = 1 - ratio;
        BudgetKey key = new BudgetKey(scope, scopeValue);
        int level = warnedLevel.getOrDefault(key, 0);
        int next = 0;
        for (int i = level; i < warnRatios.size(); i++) {
            if (usedRatio >= warnRatios.get(i)) next = i + 1;
        }
        if (next > level) {
            warnedLevel.put(key, next);
            // 软预算：只记录，不阻断（学习版 4.6 预警语义）
            log.warn("预算预警 scope={} value={} 已用 {}%（上限 {}）",
                    scope, scopeValue, Math.round(usedRatio * 100), b.limit());
            metrics.budgetWarning(scope.name(), scopeValue);
        }
    }

    /** 周期切换的惰性重置：读写预算前调用（必须在 synchronized 内） */
    private static void lazyResetIfNeeded(Budget b) {
        if (b.periodChanged(System.currentTimeMillis())) {
            b.resetPeriod(b.period().periodId(System.currentTimeMillis()));
        }
    }

    /** 对账落库：把内存 used 快照写回 DB（由 UsageLedger 定时 flush 触发） */
    public void flushUsed() {
         for (Map.Entry<BudgetKey, Budget> e : budgets.entrySet()) {
            Budget b = e.getValue();
            synchronized (b) {
                lazyResetIfNeeded(b);
                dao.updateUsed(b.scope(), b.scopeValue(), b.limitType(), b.period(), b.used());
            }
        }
    }

    /** 全部预算（管理端点列表用） */
    public List<Budget> allBudgets() {
        return List.copyOf(budgets.values());
    }
}
