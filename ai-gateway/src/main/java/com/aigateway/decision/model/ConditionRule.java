package com.aigateway.decision.model;

import java.util.List;

/**
 * 条件规则：命中后把候选集覆盖为 select 列出的 instanceId（只收窄、不扩大）。
 */
public record ConditionRule(Condition condition, List<String> select) {}
