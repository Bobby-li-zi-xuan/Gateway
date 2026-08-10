package com.aigateway.decision.engine;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.decision.filter.FilterChain;
import com.aigateway.decision.model.Policy;
import com.aigateway.decision.model.RoutingDecision;
import com.aigateway.decision.model.ScoringDetail;
import com.aigateway.decision.policy.PolicyManager;
import com.aigateway.decision.scheduler.Scheduler;
import com.aigateway.decision.scorer.Scorer;
import com.aigateway.observability.GatewayMetrics;
import com.aigateway.plugin.context.PluginContext;
import com.aigateway.plugin.engine.PluginEngine;
import com.aigateway.plugin.spi.PluginPhase;
import com.aigateway.state.registry.ModelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 决策引擎端到端编排单测（计划 20.1，H6）：
 * 插件→过滤→打分→调度全链路；503 语义保持。
 */
class DecisionEngineTest {

    private final ModelRegistry registry = mock(ModelRegistry.class);
    private final PluginEngine pluginEngine = mock(PluginEngine.class);
    private final FilterChain filterChain = mock(FilterChain.class);
    private final Scorer scorer = mock(Scorer.class);
    private final Scheduler scheduler = mock(Scheduler.class);
    private final PolicyManager policyManager = mock(PolicyManager.class);
    private final GatewayMetrics metrics = mock(GatewayMetrics.class);
    private DecisionEngine engine;

    private final ChatRequest request = new ChatRequest("qwen",
            List.of(new ChatRequest.Message("user", "hi")), false, null, null);

    private final ModelInstance candidate = new ModelInstance("mock-a:qwen-large", "qwen",
            "mock-a", "qwen-large", 1, null, 0.001, 0.002, 0.9, 1000L);

    private final Policy balanced = new Policy("balanced", Policy.PolicyType.MULTI_OBJECTIVE,
            Map.of("latency", 0.25, "cost", 0.25, "quality", 0.25, "health", 0.25),
            List.of(), null);

    @BeforeEach
    void setUp() {
        engine = new DecisionEngine(registry, pluginEngine, filterChain,
                scorer, scheduler, policyManager, metrics);
    }

    private RoutingDecision decision() {
        return new RoutingDecision("req-1", "qwen", "balanced", candidate,
                List.of(), List.of(), Map.of(), null);
    }

    @Test
    void decide_shouldRunPipelineAndReturnDecision() {
        when(registry.findByAlias("qwen")).thenReturn(List.of(candidate));
        when(filterChain.apply(any(), any(), any())).thenReturn(List.of(candidate));
        when(policyManager.resolve("qwen")).thenReturn(balanced);
        when(scheduler.effectivePolicy(anyString(), any(), any())).thenReturn(balanced);
        when(scorer.score(any(), any(), any(), any())).thenReturn(
                List.of(new ScoringDetail("mock-a:qwen-large", null, null, Map.of(), 1.0)));
        when(scheduler.decide(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(decision());

        DecisionEngine.DecisionOutcome outcome = engine.decide(request, "req-1", Map.of());

        assertThat(outcome.decision().primary().instanceId()).isEqualTo("mock-a:qwen-large");
        assertThat(outcome.context().decision()).isNotNull(); // 决策已写入上下文
        verify(pluginEngine).runPhase(PluginPhase.BEFORE_DECISION, outcome.context());
        verify(pluginEngine).runPhase(PluginPhase.AFTER_DECISION, outcome.context());
        verify(metrics).decision(any(RoutingDecision.class));
    }

    @Test
    void unknownModel_shouldReturn503() {
        when(registry.findByAlias("unknown")).thenReturn(List.of());

        assertThatThrownBy(() -> engine.decide(request, "req-1", Map.of()))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> {
                    assertThat(((GatewayException) e).getStatus()).isEqualTo(503);
                    assertThat(((GatewayException) e).getType()).isEqualTo("no_available_model");
                });
    }

    @Test
    void streamingRequest_shouldWriteRequiredCapabilitySignal() {
        when(registry.findByAlias("qwen")).thenReturn(List.of(candidate));
        when(filterChain.apply(any(), any(), any())).thenReturn(List.of(candidate));
        when(policyManager.resolve("qwen")).thenReturn(balanced);
        when(scheduler.effectivePolicy(anyString(), any(), any())).thenReturn(balanced);
        when(scorer.score(any(), any(), any(), any())).thenReturn(List.of());
        when(scheduler.decide(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(decision());

        ChatRequest streaming = new ChatRequest("qwen",
                List.of(new ChatRequest.Message("user", "hi")), true, null, null);

        DecisionEngine.DecisionOutcome outcome = engine.decide(streaming, "req-1", Map.of());

        // CapabilityFilter 消费的信号在决策前由引擎写入
        assertThat(outcome.context().signals().getStringSet("required_capabilities"))
                .contains(java.util.Set.of("streaming"));
    }
}
