package com.aigateway.decision.scheduler;

import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.decision.model.Policy;
import com.aigateway.decision.model.RoutingDecision;
import com.aigateway.decision.model.ScoringDetail;
import com.aigateway.plugin.context.Signals;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多目标策略单测（P6）：
 * 打分明细与候选数量不一致时抛包装的 GatewayException，
 * 而不是裸抛 IndexOutOfBoundsException。
 */
class MultiObjectiveStrategyTest {

    private final MultiObjectiveStrategy strategy = new MultiObjectiveStrategy();

    private final Policy policy = new Policy("balanced", Policy.PolicyType.MULTI_OBJECTIVE,
            Map.of("latency", 0.25, "cost", 0.25, "quality", 0.25, "health", 0.25),
            List.of(), null);

    private ModelInstance instance(String id) {
        return new ModelInstance(id, "qwen", "mock-a", id.split(":")[1],
                1, null, 0.001, 0.002, 0.9, 1000L);
    }

    private ScoringDetail detail(String instanceId, double score) {
        return new ScoringDetail(instanceId, null, null, Map.of(), score);
    }

    @Test
    void emptyDetails_shouldFailWithGatewayException() {
        List<ModelInstance> candidates = List.of(instance("mock-a:a"));

        assertThatThrownBy(() -> strategy.decide("req-1", "qwen", policy,
                candidates, List.of(), new Signals()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("打分明细与候选数量不一致");
    }

    @Test
    void mismatchedDetailCount_shouldFailWithGatewayException() {
        List<ModelInstance> candidates = List.of(instance("mock-a:a"), instance("mock-a:b"));

        assertThatThrownBy(() -> strategy.decide("req-1", "qwen", policy,
                candidates, List.of(detail("mock-a:a", 0.9)), new Signals()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("打分明细与候选数量不一致");
    }

    @Test
    void validDetails_shouldPickHighestScoreAsPrimary() {
        List<ModelInstance> candidates = List.of(instance("mock-a:a"), instance("mock-a:b"));
        List<ScoringDetail> details = List.of(detail("mock-a:a", 0.9), detail("mock-a:b", 0.1));

        RoutingDecision decision = strategy.decide("req-1", "qwen", policy,
                candidates, details, new Signals());

        assertThat(decision.primary().instanceId()).isEqualTo("mock-a:a");
        assertThat(decision.fallbackChain()).extracting(ModelInstance::instanceId)
                .containsExactly("mock-a:b");
    }
}
