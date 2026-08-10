package com.aigateway.core.service;

import com.aigateway.api.dto.ChatCompletion;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.decision.engine.DecisionEngine;
import com.aigateway.decision.engine.DecisionEngine.DecisionOutcome;
import com.aigateway.decision.log.DecisionLogStore;
import com.aigateway.decision.model.RoutingDecision;
import com.aigateway.decision.state.ModelStateStore;
import com.aigateway.execution.connector.OpenAIConnector;
import com.aigateway.observability.GatewayMetrics;
import com.aigateway.plugin.context.PluginContext;
import com.aigateway.plugin.engine.PluginEngine;
import com.aigateway.plugin.spi.PluginPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网关主流程单测（计划 20.1，H6）：决策与执行分离后的执行层语义。
 *
 * 覆盖：主选失败 → fallbackChain[0]；全失败 → 502；决策失败 → 503 且不落决策日志；
 * 成功路径触发 AFTER_EXECUTION 钩子。
 */
class ChatGatewayServiceTest {

    private final DecisionEngine decisionEngine = mock(DecisionEngine.class);
    private final DecisionLogStore decisionLogStore = mock(DecisionLogStore.class);
    private final OpenAIConnector connector = mock(OpenAIConnector.class);
    private final ModelStateStore stateStore = mock(ModelStateStore.class);
    private final GatewayMetrics metrics = mock(GatewayMetrics.class);
    private final PluginEngine pluginEngine = mock(PluginEngine.class);
    private ChatGatewayService service;

    private static final ChatCompletion COMPLETION = new ChatCompletion(
            "chatcmpl-test", "chat.completion", 1L, "qwen-small",
            List.of(new ChatCompletion.Choice(0,
                    new ChatCompletion.Choice.Message("assistant", "ok"), "stop")),
            new ChatCompletion.Usage(1, 1, 2));

    private static final Map<String, String> METADATA = Map.of("canary_group", "stable");

    @BeforeEach
    void setUp() {
        service = new ChatGatewayService(decisionEngine, decisionLogStore,
                connector, stateStore, metrics, pluginEngine);
    }

    private ModelInstance instance(String id) {
        return new ModelInstance(id, "qwen", "mock", id.split(":")[1], 1, null,
                0.0, 0.0, 0.5, 1000L);
    }

    private ChatRequest request() {
        return new ChatRequest("qwen", List.of(new ChatRequest.Message("user", "hi")),
                false, null, null);
    }

    private DecisionOutcome outcome(RoutingDecision decision) {
        PluginContext ctx = new PluginContext(request(), "req-1", "qwen",
                METADATA, decision.fallbackChain().isEmpty()
                        ? List.of(decision.primary())
                        : List.of(decision.primary(), decision.fallbackChain().get(0)));
        return new DecisionOutcome(decision, ctx);
    }

    private RoutingDecision decision(List<ModelInstance> fallbackChain) {
        return new RoutingDecision("req-1", "qwen", "balanced",
                instance("mock-a:qwen-large"), fallbackChain,
                List.of(), Map.of(), null);
    }

    @Test
    void primaryFails_shouldFallbackToChainHead() {
        RoutingDecision decision = decision(List.of(instance("mock-b:qwen-small")));
        when(decisionEngine.decide(any(), anyString(), any())).thenReturn(outcome(decision));
        AtomicInteger calls = new AtomicInteger();
        when(connector.complete(any(), any())).thenAnswer(inv -> {
            if (calls.getAndIncrement() == 0) {
                throw new RuntimeException("boom");
            }
            return COMPLETION;
        });

        ChatCompletion result = service.complete(request(), "req-1", METADATA);

        assertThat(result).isEqualTo(COMPLETION);
        verify(decisionLogStore).record(decision);              // 决策明细先落库
        verify(metrics).failure("qwen", "mock-a:qwen-large");   // 主选失败计数
        verify(metrics).success("qwen", "mock-b:qwen-small");   // 降级成功计数
        // 执行阶段钩子：BEFORE_EXECUTION（主选前）+ AFTER_EXECUTION（降级成功后）
        verify(pluginEngine).runPhase(eq(PluginPhase.BEFORE_EXECUTION), any(PluginContext.class));
        verify(pluginEngine).runPhase(eq(PluginPhase.AFTER_EXECUTION), any(PluginContext.class));
    }

    @Test
    void primaryAndBackupFail_shouldReturn502() {
        RoutingDecision decision = decision(List.of(instance("mock-b:qwen-small")));
        when(decisionEngine.decide(any(), anyString(), any())).thenReturn(outcome(decision));
        when(connector.complete(any(), any())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> service.complete(request(), "req-1", METADATA))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> {
                    assertThat(((GatewayException) e).getStatus()).isEqualTo(502);
                    assertThat(((GatewayException) e).getType()).isEqualTo("upstream_failed");
                });
        verify(metrics, never()).success(anyString(), anyString());
        // 最终失败触发 ON_ERROR 钩子（eq + matcher：不能与 raw 值混用）
        verify(pluginEngine).runPhase(eq(PluginPhase.ON_ERROR), any(PluginContext.class));
    }

    @Test
    void primaryFailsWithoutBackup_shouldReturn502() {
        RoutingDecision decision = decision(List.of());
        when(decisionEngine.decide(any(), anyString(), any())).thenReturn(outcome(decision));
        when(connector.complete(any(), any())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> service.complete(request(), "req-1", METADATA))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> {
                    assertThat(((GatewayException) e).getStatus()).isEqualTo(502);
                    assertThat(((GatewayException) e).getType()).isEqualTo("upstream_failed");
                });
        // 主选只调用一次，不重试
        verify(connector).complete(any(), any());
    }

    @Test
    void decisionFails_shouldPropagate503WithoutRecordingLog() {
        when(decisionEngine.decide(any(), anyString(), any()))
                .thenThrow(new GatewayException(503, "no_available_model", "候选为空"));

        assertThatThrownBy(() -> service.complete(request(), "req-1", METADATA))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> {
                    assertThat(((GatewayException) e).getStatus()).isEqualTo(503);
                    assertThat(((GatewayException) e).getType()).isEqualTo("no_available_model");
                });
        verify(decisionLogStore, never()).record(any());
        verify(connector, never()).complete(any(), any());
    }

    @Test
    void successPath_shouldRecordSuccessAndTriggerAfterExecution() {
        RoutingDecision decision = decision(List.of());
        when(decisionEngine.decide(any(), anyString(), any())).thenReturn(outcome(decision));
        when(connector.complete(any(), any())).thenReturn(COMPLETION);

        ChatCompletion result = service.complete(request(), "req-1", METADATA);

        assertThat(result).isEqualTo(COMPLETION);
        verify(stateStore).recordSuccess(eq("mock-a:qwen-large"), anyLong());
        verify(stateStore).beginRequest("mock-a:qwen-large");
        verify(stateStore).endRequest("mock-a:qwen-large");
        verify(metrics).success("qwen", "mock-a:qwen-large");
        verify(pluginEngine).runPhase(eq(PluginPhase.AFTER_EXECUTION), any(PluginContext.class));
    }
}
