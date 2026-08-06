package com.aigateway.decision.model;

/**
 * 实例实时状态：决策引擎输入之一。
 * healthy 以 HealthChecker 快照为准（不在本对象重复存储）；V3 追加熔断/冷却状态。
 */
public record ModelState(
        String instanceId,
        double ewmaLatencyMs,    // 指数移动平均延迟（Scorer 的 latency 因子）
        double errorRate,        // 0~1，EWMA 错误率（health 因子输入）
        int inFlight,            // 当前并发数（load 雏形，V3 负载因子用）
        long lastUpdatedAt       // 最近一次更新时间戳
) {}
