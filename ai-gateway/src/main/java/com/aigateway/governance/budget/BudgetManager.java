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
 * 预算管理器：管「总量」的第二层治理（🖊 H3：逻辑全部手敲，对照 10.2 / 学习版 4.6）。
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
 * TODO H3 手敲清单（未完成前调用对应功能会直接报错）：
 * - loadFromDb()       ：启动加载 DB 预算定义 + used 快照；
 * - upsert()           ：创建/覆盖预算定义（limit <= 0 抛 invalid_request）；
 * - precheck()         ：预检剩余额度（不改变任何状态）；
 * - remainingRatio()   ：剩余比率（Scorer 信号输入）；
 * - consume()          ：结算扣减（锁内比较后写，不超扣）；失败返回 exceeded；
 * - checkWarnings()    ：跨过第 i 档阈值才记一次预警（日志 + 指标）；
 * - lazyResetIfNeeded()：周期切换的惰性重置（必须在 synchronized 内）；
 * - flushUsed()        ：对账落库（UsageLedger 定时触发）。
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
        // TODO H3：loadFromDb() 启动加载（dao.findAll() → budgets.put）
    }

    /** 创建或替换一个预算定义（管理端点调用；同 key 覆盖） */
    public Budget upsert(BudgetScope scope, String scopeValue, LimitType limitType,
                         BudgetPeriod period, double limit) {
        // TODO H3：limit <= 0 抛 GatewayException(400, "invalid_request")；
        // new Budget(...) → budgets.put → warnedLevel 归零 → dao.upsert → 返回
        throw new GatewayException(500, "not_implemented",
                "TODO H3：预算定义未实现（手敲 BudgetManager.upsert）");
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
        // TODO H3：取预算（未配置 = 不限返回 true）→ synchronized → lazyReset →
        // 整数比较 usedMicro + unit <= limitUnit
        throw new GatewayException(500, "not_implemented",
                "TODO H3：预算预检未实现（手敲 BudgetManager.precheck）");
    }

    /** 剩余比率（Scorer 的 budget_remaining_ratio 信号输入）；未配置预算返回 1.0 */
    public double remainingRatio(BudgetScope scope, String scopeValue) {
        // TODO H3：取预算（未配置返回 1.0）→ synchronized → lazyReset → b.remainingRatio()
        throw new GatewayException(500, "not_implemented",
                "TODO H3：剩余比率未实现（手敲 BudgetManager.remainingRatio）");
    }

    /**
     * 结算扣减：按实际用量原子扣减（只有成功响应才调用）。
     * 与 precheck 同量纲约定：tokenAmount / costAmount 双值传入，按预算 limitType 取用。
     * @return OK = 扣减成功；EXCEEDED = 超出上限（拒绝，调用方回 429 budget_exceeded）
     */
    public ConsumeResult consume(BudgetScope scope, String scopeValue,
                                 long tokenAmount, double costAmount) {
        // TODO H3：取预算（未配置 = 不扣返回 ok(1.0)）→ synchronized → lazyReset →
        // 锁内「used + unit > limitUnit 则 exceeded；否则 set(used + unit)」→
        // 锁外 checkWarnings → ok(remainingRatio)
        throw new GatewayException(500, "not_implemented",
                "TODO H3：预算扣减未实现（手敲 BudgetManager.consume）");
    }

    /** 扣减结果：remainingRatio 供上层写信号/指标 */
    public record ConsumeResult(boolean ok, double remainingRatio) {
        static ConsumeResult ok(double ratio)      { return new ConsumeResult(true, ratio); }
        static ConsumeResult exceeded(double ratio) { return new ConsumeResult(false, ratio); }
    }

    /** 预警：跨过第 i 档阈值（70% / 90%）才记一次（避免每请求重复刷日志） */
    private void checkWarnings(BudgetScope scope, String scopeValue, Budget b) {
        // TODO H3：计算 usedRatio；从当前档位起逐档检查，跨过新档 → 记日志 + metrics.budgetWarning
        throw new GatewayException(500, "not_implemented",
                "TODO H3：预算预警未实现（手敲 BudgetManager.checkWarnings）");
    }

    /** 周期切换的惰性重置：读写预算前调用（必须在 synchronized 内） */
    private static void lazyResetIfNeeded(Budget b) {
        // TODO H3：periodChanged(now) 命中 → b.resetPeriod(b.period().periodId(now))
        throw new GatewayException(500, "not_implemented",
                "TODO H3：周期惰性重置未实现（手敲 BudgetManager.lazyResetIfNeeded）");
    }

    /** 对账落库：把内存 used 快照写回 DB（由 UsageLedger 定时 flush 触发） */
    public void flushUsed() {
        // TODO H3：逐预算 synchronized → lazyReset → dao.updateUsed(...)
        throw new GatewayException(500, "not_implemented",
                "TODO H3：预算对账落库未实现（手敲 BudgetManager.flushUsed）");
    }

    /** 全部预算（管理端点列表用） */
    public List<Budget> allBudgets() {
        return List.copyOf(budgets.values());
    }
}
