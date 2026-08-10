package com.aigateway.decision.scheduler;

import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.decision.model.Condition;
import com.aigateway.decision.model.ConditionRule;
import com.aigateway.decision.model.Policy;
import com.aigateway.decision.model.Policy.PolicyType;
import com.aigateway.decision.model.RoutingDecision;
import com.aigateway.decision.model.ScoringDetail;
import com.aigateway.decision.policy.PolicyManager;
import com.aigateway.plugin.context.Signals;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 调度器：按策略类型分派（多目标 / 加权随机 / 条件路由），输出 RoutingDecision。
 *
 *
 * 策略类（MultiObjectiveStrategy / WeightedRandomStrategy / ConditionalStrategy）
 * 已由脚手架提供，手敲时直接组合它们即可。
 */
@Component
public class Scheduler {

    private final PolicyManager policyManager; // CONDITIONAL 未命中时回退查询

    private final MultiObjectiveStrategy multiObjectiveStrategy;
    private final WeightedRandomStrategy weightedRandomStrategy;
    private final ConditionalStrategy conditionalStrategy;
    public Scheduler(PolicyManager policyManager,
                     MultiObjectiveStrategy multiObjectiveStrategy,
                     WeightedRandomStrategy weightedRandomStrategy,
                     ConditionalStrategy conditionalStrategy) {
        this.policyManager = policyManager;
        this.multiObjectiveStrategy = multiObjectiveStrategy;
        this.weightedRandomStrategy = weightedRandomStrategy;
        this.conditionalStrategy = conditionalStrategy;
    }

    /**
     * 决策前求"有效策略"（打分权重与调度排序一致的关键）：
     * CONDITIONAL 按信号求值——命中返回自身（调度时走规则覆盖，权重无效）；
     * 未命中返回 defaultStrategy（其权重用于打分，避免回退时按默认均衡权重误排序）；
     * 其余类型原样返回。DecisionEngine 先调它取权重，再交给 Scorer 打分。
     * ⚠️ 分派契约：decide() 必须接收本方法的解析结果
     *（见下方设计要点第 6 条）。
     */
    public Policy effectivePolicy(String alias, Policy policy, Signals signals) {
        if (policy.type() != PolicyType.CONDITIONAL) {
            return policy;
        }
        for (ConditionRule rule : policy.rules()) {
            if (matches(rule.condition(), signals)) {
                return policy;
            }
        }
        return policyManager.byName(policy.defaultStrategy());
    }

    /** 入口：按策略类型分派到对应策略类，输出 RoutingDecision */
    public RoutingDecision decide(String requestId, String alias, Policy policy,
                                  List<ModelInstance> candidates,
                                  List<ScoringDetail> details,
                                  Signals signals) {
        return switch(policy.type()){
            case MULTI_OBJECTIVE -> multiObjectiveStrategy.decide(requestId, alias, policy, candidates, details, signals);
            case WEIGHTED_RANDOM -> weightedRandomStrategy.decide(requestId, alias, policy, candidates, signals, null);
            case CONDITIONAL -> conditionalStrategy.decide(requestId, alias, policy, candidates, signals);
        };
    }

    /** 条件求值：信号存在且满足 op 才命中 effectivePolicy 用；
     * ConditionalStrategy 内同样实现一份） */
    private boolean matches(Condition condition, Signals signals) {
        Optional<Object> actual = signals.get(condition.signal());
        if (actual.isEmpty()) {
            return false;
        }
        String v = String.valueOf(actual.get());
        return switch (condition.op()) {
            case EQ -> v.equals(String.valueOf(condition.value()));
            case NE -> !v.equals(String.valueOf(condition.value()));
            case IN -> ((List<?>) condition.value()).stream()
                    .map(String::valueOf).anyMatch(v::equals);
        };
    }
}   
