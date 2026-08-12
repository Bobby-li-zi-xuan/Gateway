package com.aigateway.execution.model;

/**
 * 运行期不可变策略快照：一次请求从"缺省 → 渠道覆盖 → 请求级覆盖"合并后的最终策略。
 * 由 ExecutionPolicyManager 统一合并与校验，执行器只消费本快照。
 *
 * @param totalMs         整条降级链总时长（所有候选/尝试共享）
 * @param timeout         分层超时
 * @param retry           重试配置
 * @param circuitBreaker  熔断配置
 * @param cooldown        冷却配置
 */
public record ExecutionPolicies(
        long totalMs,
        TimeoutPolicy timeout,
        RetryPolicy retry,
        CircuitBreakerConfig circuitBreaker,
        CooldownConfig cooldown
) {}
