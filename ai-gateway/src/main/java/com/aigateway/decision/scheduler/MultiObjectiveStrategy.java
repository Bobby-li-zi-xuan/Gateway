package com.aigateway.decision.scheduler;

import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.decision.model.Policy;
import com.aigateway.decision.model.RoutingDecision;
import com.aigateway.decision.model.ScoringDetail;
import com.aigateway.plugin.context.Signals;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 没有特殊约束的日常流量，追求"整体最好"
 * 多目标策略：按 finalScore 降序排序，同分按 instanceId 字典序
 * （确定性）；主选 = 第一名，降级链 = 其余按得分降序。
 */
@Component
public class MultiObjectiveStrategy {

    public RoutingDecision decide(String requestId, String alias, Policy policy,
                                  List<ModelInstance> candidates,
                                  List<ScoringDetail> details,
                                  Signals signals) {
        // 打分明细与候选必须一一对应（Scorer 契约），不一致说明上游 bug，
        // 提前抛包装异常而不是让 ordered.get(0) 裸抛 IndexOutOfBoundsException
        if (details.size() != candidates.size()) {
            throw new GatewayException(500, "internal_error",
                    "打分明细与候选数量不一致: details=" + details.size()
                            + ", candidates=" + candidates.size());
        }
        List<ScoringDetail> sorted = details.stream()
                .sorted(Comparator.comparingDouble(ScoringDetail::finalScore).reversed()
                        .thenComparing(ScoringDetail::instanceId))
                .toList();
        List<ModelInstance> ordered = sorted.stream()
                .map(d -> findById(candidates, d.instanceId()))
                .toList();
        return new RoutingDecision(requestId, alias, policy.name(),
                ordered.get(0), ordered.subList(1, ordered.size()),
                sorted, signals.snapshot(), null);
    }

    private ModelInstance findById(List<ModelInstance> candidates, String instanceId) {
        return candidates.stream()
                .filter(c -> c.instanceId().equals(instanceId))
                .findFirst()
                .orElseThrow(() -> new GatewayException(500, "internal_error",
                        "打分明细与候选不一致: " + instanceId));
    }
}
