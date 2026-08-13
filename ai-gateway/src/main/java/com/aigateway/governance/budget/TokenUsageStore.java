package com.aigateway.governance.budget;

import com.aigateway.governance.model.ApiToken;
import com.aigateway.governance.persistence.TokenDao;
import com.aigateway.governance.persistence.UsageDao;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 令牌额度账本（内存热路径，脚手架完整实现，对照《版本4-详细实施计划》12.2）：
 * - COST 类型按微美元累计（整数防精度问题），TOKEN 类型按 token 数累计；
 * - 启动时从 usage_record 按 tokenId 聚合恢复（账本即用量表，重启不丢账；
 *   无需在 api_token 表冗余 used_value 快照列——用量表就是持久化账本）；
 * - consume 用 CAS 循环防超扣（与 BudgetManager 同思想）。
 */
@Component
public class TokenUsageStore {

    private final TokenDao tokenDao;
    private final UsageDao usageDao;
    private final Map<String, AtomicLong> used = new ConcurrentHashMap<>(); // tokenId -> 微美元/token 数

    public TokenUsageStore(TokenDao tokenDao, UsageDao usageDao) {
        this.tokenDao = tokenDao;
        this.usageDao = usageDao;
        restore();
    }

    /**
     * 重启恢复：用量表 SUM 即令牌账本（验收标准 1「重启不丢账」的落点）。
     * 换算口径：restore 必须按令牌 quotaType 分支——
     * COST 令牌从 SUM(cost) 转微美元、TOKEN 令牌从 SUM(token_in + token_out) 取整；
     * 不按 quotaType 换算会把美元当 token 数（或反之），重启后额度对不上账。
     */
    private void restore() {
        Map<String, ApiToken> tokens = tokenDao.findAll().stream()
                .collect(Collectors.toMap(ApiToken::id, t -> t));
        for (Map<String, Object> row : usageDao.sumByTokenId()) {
            String tokenId = (String) row.get("token_id");
            ApiToken token = tokens.get(tokenId);
            if (token == null) continue;                     // 已吊销/删除的令牌跳过
            long unit = "TOKEN".equals(token.quotaType())
                    ? ((Number) row.get("total_tokens")).longValue()
                    : Math.round(((Number) row.get("total_cost")).doubleValue() * 1_000_000);
            used.computeIfAbsent(tokenId, k -> new AtomicLong()).addAndGet(unit);
        }
    }

    /** 令牌剩余额度（quotaLimit - 已用）；返回负数表示已超支（预检用） */
    public double remaining(ApiToken token) {
        if (token.unlimited()) return Double.MAX_VALUE;
        long unit = used.computeIfAbsent(token.id(), k -> new AtomicLong()).get();
        return limitToUnit(token) - unit;
    }

    /** 剩余比率 0~1（Scorer 信号）；无限额度恒为 1 */
    public double remainingRatio(ApiToken token) {
        if (token.unlimited()) return 1.0;
        long limit = limitToUnit(token);
        long u = used.computeIfAbsent(token.id(), k -> new AtomicLong()).get();
        return limit <= 0 ? 0 : Math.max(0, Math.min(1, (limit - u) / (double) limit));
    }

    /** 能否支付 amount（预检）；amount 单位与 quotaType 一致 */
    public boolean canPay(ApiToken token, double amount) {
        return remaining(token) >= toUnit(token, amount);
    }

    /** 结算扣减：CAS 循环，不超扣 */
    public boolean consume(ApiToken token, double amount) {
        if (token.unlimited()) return true;
        AtomicLong counter = used.computeIfAbsent(token.id(), k -> new AtomicLong());
        long unit = toUnit(token, amount);
        long limit = limitToUnit(token);
        while (true) {
            long cur = counter.get();
            if (cur + unit > limit) return false;      // 超限：拒绝（由调用方回 429）
            if (counter.compareAndSet(cur, cur + unit)) return true;
        }
    }

    private long limitToUnit(ApiToken token) {
        return toUnit(token, token.quotaLimit());
    }

    private static long toUnit(ApiToken token, double amount) {
        return "TOKEN".equals(token.quotaType())
                ? Math.round(amount)
                : Math.round(amount * 1_000_000);       // COST → 微美元
    }
}
