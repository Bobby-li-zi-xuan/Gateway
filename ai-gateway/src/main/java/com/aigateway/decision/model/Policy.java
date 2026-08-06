package com.aigateway.decision.model;

import java.util.List;
import java.util.Map;

/**
 * 策略模板（打分模板管理）：由 gateway.policies 配置加载。
 * 三种类型：多目标打分 / 加权随机 / 条件路由。
 */
public record Policy(
        String name,                     // 策略名（models[].strategy 引用）
        PolicyType type,                 // MULTI_OBJECTIVE / WEIGHTED_RANDOM / CONDITIONAL
        Map<String, Double> weights,     // MULTI_OBJECTIVE 权重向量（latency/cost/quality/health）
        List<ConditionRule> rules,       // CONDITIONAL 规则（按声明顺序求值）
        String defaultStrategy           // CONDITIONAL 未命中时的回退策略名
) {
    public enum PolicyType { MULTI_OBJECTIVE, WEIGHTED_RANDOM, CONDITIONAL }
}
