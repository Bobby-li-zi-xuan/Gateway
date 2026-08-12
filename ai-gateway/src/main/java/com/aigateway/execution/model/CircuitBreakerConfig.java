package com.aigateway.execution.model;

/**
 * 熔断配置（学习版 4.4）：参数挂在配置上而非写死（对照 Portkey per-strategy circuit protection）。
 *
 * @param windowSize            环形窗口大小（统计最近 N 次调用）
 * @param minimumRequests       最少样本数（样本不足不触发熔断）
 * @param failureRateThreshold  失败率阈值（0~1）
 * @param slowCallThresholdMs   超过该延迟判定为慢调用
 * @param slowCallRateThreshold 慢调用率阈值（0~1）
 * @param openDurationMs        打开状态持续时间
 * @param halfOpenMaxRequests   半开态放行的探测请求数
 */
public record CircuitBreakerConfig(
        int windowSize,
        int minimumRequests,
        double failureRateThreshold,
        long slowCallThresholdMs,
        double slowCallRateThreshold,
        long openDurationMs,
        int halfOpenMaxRequests
) {}
