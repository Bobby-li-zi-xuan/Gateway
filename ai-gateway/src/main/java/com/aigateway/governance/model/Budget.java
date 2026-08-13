package com.aigateway.governance.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 预算定义 + 计数（对应 LiteLLM budget / Envoy QuotaPolicy）：
 * - limit 是周期上限；used 是当前周期已用量；
 * - COST 类型内部按「微美元」整数计数（double 有精度问题，结算时再转美元）；
 * - used 由 BudgetManager 用 CAS 原子扣减（H3），本类只做状态容器。
 */
public final class Budget {

    private final BudgetScope scope;
    private final String scopeValue;      // MODEL=alias、CHANNEL=channelId、GLOBAL=""
    private final LimitType limitType;
    private final BudgetPeriod period;
    private final double limit;           // TOKEN=token 数；COST=美元
    private final AtomicLong usedMicro = new AtomicLong();
    private volatile String currentPeriodId;   // 惰性重置判据

    public Budget(BudgetScope scope, String scopeValue, LimitType limitType,
                  BudgetPeriod period, double limit) {
        this.scope = scope;
        this.scopeValue = scopeValue;
        this.limitType = limitType;
        this.period = period;
        this.limit = limit;
        this.currentPeriodId = period.periodId(System.currentTimeMillis());
    }

    public BudgetScope scope() { return scope; }
    public String scopeValue() { return scopeValue; }
    public LimitType limitType() { return limitType; }
    public BudgetPeriod period() { return period; }
    public double limit() { return limit; }

    /** 内部计数句柄（BudgetManager 的扣减/重置用；锁内读写） */
    public AtomicLong usedMicro() { return usedMicro; }

    /** 当前已用量（展示口径；COST 转回美元） */
    public double used() {
        return limitType == LimitType.COST ? usedMicro.get() / 1_000_000.0 : usedMicro.get();
    }

    /** 剩余用量（预检用）；为负按 0（不提供负余额，扣减时由 CAS 保证不超扣） */
    public double remaining() { return Math.max(0, limit - used()); }

    /** 剩余比率 0~1（Scorer 的 budget_remaining_ratio 信号输入） */
    public double remainingRatio() {
        return limit <= 0 ? 0 : Math.max(0, Math.min(1, remaining() / limit));
    }

    /** 转为内部整数计数单位：TOKEN=token 数、COST=微美元 */
    public long toUnit(double amount) {
        return limitType == LimitType.COST ? Math.round(amount * 1_000_000) : Math.round(amount);
    }

    /** 周期是否已切换（BudgetManager 每次读写前调用，命中即惰性重置） */
    public boolean periodChanged(long nowMs) {
        return !currentPeriodId.equals(period.periodId(nowMs));
    }

    /** 惰性重置：新周期从 0 开始 */
    public void resetPeriod(String newPeriodId) {
        usedMicro.set(0);
        currentPeriodId = newPeriodId;
    }

    public String periodId() { return currentPeriodId; }
}
