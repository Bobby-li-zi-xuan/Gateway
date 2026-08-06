package com.aigateway.decision.model;

import java.util.Map;

/**
 * 一个候选的完整打分记录：原始值、归一化值、实际生效权重、加权总分。
 * 这是“可解释路由”的最小单位。
 */
public record ScoringDetail(
        String instanceId,
        ScoreFactors raw,                // 原始因子：延迟 ms / 成本 $ / 质量分 / 健康分
        ScoreFactors normalized,         // 归一化到 0~1（延迟/成本越低越好，质量/健康越高越好）
        Map<String, Double> weights,     // 实际生效权重（含动态调权后的值）
        double finalScore                // Σ weight * normalized
) {}
