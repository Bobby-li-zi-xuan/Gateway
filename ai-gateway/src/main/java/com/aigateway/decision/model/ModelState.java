package com.aigateway.decision.model;

import com.aigateway.execution.model.CircuitState;

/**
 * 实例实时状态：决策引擎输入之一。
 * healthy 以 HealthChecker 快照为准（不在本对象重复存储）。
 *
 * V2 字段（EWMA 延迟 / 错误率 / 并发数）+ V3 新增字段（熔断 / 慢调用 / 总调用 / 冷却），
 * 熔断状态与冷却状态写入后决策层（Scorer / Filter）可直接读取，无需适配层。
 */
public record ModelState(
        String instanceId,
        double ewmaLatencyMs,     // 指数移动平均延迟（Scorer 的 latency 因子）
        double errorRate,         // 0~1，EWMA 错误率（health 因子输入）
        int inFlight,             // 当前并发数（load 因子输入）
        long lastUpdatedAt,       // 最近一次更新时间戳
        CircuitState circuitState, // V3：熔断状态（决策层可读）
        long slowCalls,           // V3：慢调用累计计数（决策输入）
        long totalCalls,          // V3：总调用累计计数（决策输入）
        boolean cooling           // V3：渠道冷却（渠道级，按实例展示）
) {}
