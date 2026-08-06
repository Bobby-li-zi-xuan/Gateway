package com.aigateway.decision.model;

import com.aigateway.core.domain.model.ModelInstance;

import java.util.List;
import java.util.Map;

/**
 * 调度方案：决策层与执行层的唯一契约（设计文档 3.2 原则 1）。
 * 不可变 record；主选 + 降级链 + 打分明细 + 策略名。
 */
public record RoutingDecision(
        String requestId,                    // 全链路 ID
        String alias,                        // 模型别名
        String strategy,                     // 生效策略名（条件命中时 = policy.name）
        ModelInstance primary,               // 主选
        List<ModelInstance> fallbackChain,   // 降级链（V3 完整执行；V2 只生成 + 最小降级）
        List<ScoringDetail> scoringDetails,  // 打分明细（可解释路由）
        Map<String, Object> signals,         // 决策时信号快照（日志/复盘用）
        String conditionalRuleHit            // 命中的规则描述；未命中为 null
) {}
