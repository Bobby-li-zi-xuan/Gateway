package com.aigateway.execution.model;

/**
 * 渠道冷却配置（学习版 4.5）：比熔断轻，管"短时抽风"。
 *
 * @param consecutiveFailures 连续失败阈值
 * @param cooldownMs          冷却时长
 * @param maxCooldownMs       恢复探测失败翻倍的上限
 * @param recoveryProbe       恢复探测开关（到期后第一个请求视为探测）
 */
public record CooldownConfig(
        int consecutiveFailures,
        long cooldownMs,
        long maxCooldownMs,
        boolean recoveryProbe
) {}
