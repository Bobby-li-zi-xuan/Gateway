package com.aigateway.decision.scheduler;

import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.core.service.WeightedRandomPicker;
import com.aigateway.decision.model.Policy;
import com.aigateway.decision.model.RoutingDecision;
import com.aigateway.plugin.context.Signals;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 候选之间"不分优劣"或"想按比例试探"，需要把流量摊开
 * 加权随机策略：转盘算法。
 * 主选 = 加权随机结果；降级链 = 其余候选按 weight 降序（V3 使用）。
 */
@Component
public class WeightedRandomStrategy {

    public RoutingDecision decide(String requestId, String alias, Policy policy,
                                  List<ModelInstance> candidates,
                                  Signals signals, String ruleHit) {
        ModelInstance primary = new WeightedRandomPicker(candidates).pick()
                .orElseThrow(() -> new GatewayException(503, "no_available_model", "候选为空"));
        List<ModelInstance> rest = candidates.stream()
                .filter(c -> !c.instanceId().equals(primary.instanceId()))
                .sorted(Comparator.comparingInt(ModelInstance::weight).reversed())
                .toList();
        return new RoutingDecision(requestId, alias, policy.name(), primary, rest,
                List.of(), signals.snapshot(), ruleHit);
    }
}
