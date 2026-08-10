package com.aigateway.decision.scheduler;

import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.decision.model.Condition;
import com.aigateway.decision.model.ConditionRule;
import com.aigateway.decision.model.Policy;
import com.aigateway.decision.model.RoutingDecision;
import com.aigateway.plugin.context.Signals;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 存在必须优先于"分数"的硬性规则
 * 条件路由策略：规则按声明顺序求值，命中即覆盖候选集。
 *
 */
@Component
public class ConditionalStrategy {

    private final WeightedRandomStrategy weightedRandomStrategy;

    public ConditionalStrategy(WeightedRandomStrategy weightedRandomStrategy) {
        this.weightedRandomStrategy = weightedRandomStrategy;
    }

    /** 条件路由：按声明顺序求值，命中即覆盖候选集，再在规则目标内加权随机（+ 生成降级链） */
    public RoutingDecision decide(String requestId, String alias, Policy policy,
                                  List<ModelInstance> candidates,
                                  Signals signals) {
        for(int i = 0; i < policy.rules().size(); i ++){
            ConditionRule rule = policy.rules().get(i);
            if(matches(rule.condition(), signals)){
                List<ModelInstance> selected = candidates.stream()
                                .filter(c -> rule.select().contains(c.instanceId()))
                                .toList();
                if(!selected.isEmpty()){
                    String hit = "rule#" + i + ":" + rule.condition().signal()
                                + "=" + rule.condition().value();
                    return weightedRandomStrategy.decide(requestId, alias, policy, selected, signals, hit);
                }
            }
        }
        // 防御性兜底：effectivePolicy 已保证命中才会分派到本类，此处不应到达
        throw new GatewayException(503, "no_available_model", "条件规则未命中（effectivePolicy 应已拦截）");
    }

    /** 条件求值：信号存在且满足 op 才命中（与 Scheduler.matches 同一份语义） */
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
