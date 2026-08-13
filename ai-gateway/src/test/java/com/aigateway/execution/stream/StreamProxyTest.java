package com.aigateway.execution.stream;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.decision.state.ModelStateStore;
import com.aigateway.decision.state.StateEventPipeline;
import com.aigateway.execution.circuit.CircuitBreakerRegistry;
import com.aigateway.execution.connector.OpenAIConnector;
import com.aigateway.execution.cooldown.CooldownManager;
import com.aigateway.execution.model.CircuitBreakerConfig;
import com.aigateway.execution.model.CooldownConfig;
import com.aigateway.execution.model.ExecutionPolicies;
import com.aigateway.execution.model.Failure;
import com.aigateway.execution.model.FailureType;
import com.aigateway.execution.model.RetryPolicy;
import com.aigateway.execution.model.StreamSession;
import com.aigateway.execution.model.TimeoutPolicy;
import com.aigateway.execution.model.UpstreamCallException;
import com.aigateway.execution.policy.ExecutionPolicyManager;
import com.aigateway.observability.GatewayMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H6 单测（详细实施计划 18.1 H6 行）：流式代理四件事——
 * SSE 规范化、错误事件透传、首字节后不降级、客户端断开取消上游。
 *
 * 依赖说明：9 个依赖全部 mock；connector.stream 用 mock 回调模拟上游 chunk
 * （或直接抛 ClientDisconnectedException / UpstreamCallException）。
 */
class StreamProxyTest {

    private static final ModelInstance CANDIDATE_A = new ModelInstance(
            "mock-a:qwen-large", "qwen", "mock-a", "qwen-large", 1, null,
            0.001, 0.002, 0.9, 1000L);
    private static final ModelInstance CANDIDATE_B = new ModelInstance(
            "mock-b:qwen-small", "qwen", "mock-b", "qwen-small", 1, null,
            0.001, 0.002, 0.9, 1000L);
    private static final ChatRequest REQUEST = new ChatRequest(
            "qwen", List.of(new ChatRequest.Message("user", "hi")), true, null, null);
    private static final ExecutionPolicies POLICIES = new ExecutionPolicies(
            60_000,
            TimeoutPolicy.defaults(),
            new RetryPolicy(1, true, true, 5_000, 100, 2_000, 0.2,
                    Set.of(429, 500, 502, 503, 504)),
            new CircuitBreakerConfig(100, 5, 0.5, 10_000, 0.5, 30_000, 1),
            new CooldownConfig(5, 30_000, 300_000, true));

    private static ChatChunk chunk(String id, long created, String model,
                                   ChatChunk.StreamError error) {
        return new ChatChunk(id, "chat.completion.chunk", created, model,
                List.of(new ChatChunk.ChunkChoice(0, new ChatChunk.ChunkChoice.Delta("你好"), null)),
                null, error);
    }

    private final OpenAIConnector connector = mock(OpenAIConnector.class);
    private final CircuitBreakerRegistry circuitBreakers = mock(CircuitBreakerRegistry.class);
    private final CooldownManager cooldowns = mock(CooldownManager.class);
    private final ExecutionPolicyManager policyManager = mock(ExecutionPolicyManager.class);
    private final ModelStateStore stateStore = mock(ModelStateStore.class);
    private final StateEventPipeline stateEvents = mock(StateEventPipeline.class);
    private final GatewayMetrics metrics = mock(GatewayMetrics.class);
    private final MeteringCallback metering = mock(MeteringCallback.class);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private StreamProxy proxy;

    @BeforeEach
    void setUp() {
        when(policyManager.forRequest(anyString(), any())).thenReturn(POLICIES);
        when(circuitBreakers.allowRequest(anyString())).thenReturn(true);
        when(cooldowns.isCooling(anyString())).thenReturn(false);
        proxy = new StreamProxy(connector, circuitBreakers, cooldowns, policyManager,
                stateStore, stateEvents, metrics, metering, scheduler);
    }

    /** SSE 规范化：首个非空 id/created/model 记入状态，后续 chunk 缺字段时复用（整个流共享响应 ID） */
    @Test
    void sseNormalization_shouldFillMissingFields() throws Exception {
        ChatChunk first = chunk("c1", 123, "m", null);
        ChatChunk missing = chunk(null, 0, null, null);
        when(connector.stream(any(), any(), any(), any())).thenAnswer(inv -> {
            Consumer<ChatChunk> consumer = inv.getArgument(3);
            consumer.accept(first);
            consumer.accept(missing);
            return mock(StreamSession.class);
        });

        List<ChatChunk> received = new ArrayList<>();
        proxy.streamChain(List.of(CANDIDATE_A), REQUEST, "r1", Map.of(), received::add);

        assertThat(received).hasSize(2);
        assertThat(received.get(1).id()).isEqualTo("c1");        // 复用首个非空 id
        assertThat(received.get(1).created()).isEqualTo(123);
        assertThat(received.get(1).model()).isEqualTo("m");
        assertThat(received.get(1).object()).isEqualTo("chat.completion.chunk"); // object 固定
    }

    /** 错误事件透传：上游 error chunk → 客户端收到统一错误体（不丢失） */
    @Test
    void errorEvent_shouldPassThrough() throws Exception {
        ChatChunk errorChunk = chunk("c1", 123, "m",
                new ChatChunk.StreamError("upstream_error", "模型配额耗尽"));
        when(connector.stream(any(), any(), any(), any())).thenAnswer(inv -> {
            Consumer<ChatChunk> consumer = inv.getArgument(3);
            consumer.accept(errorChunk);
            return mock(StreamSession.class);
        });

        List<ChatChunk> received = new ArrayList<>();
        proxy.streamChain(List.of(CANDIDATE_A), REQUEST, "r1", Map.of(), received::add);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).error()).isNotNull();
        assertThat(received.get(0).error().type()).isEqualTo("upstream_error");
    }

    /** 首字节后失败：502 stream_interrupted，下一个候选不被调用 */
    @Test
    void failureAfterFirstByte_shouldNotDegrade() throws Exception {
        when(connector.stream(any(), any(), any(), any())).thenAnswer(inv -> {
            Consumer<ChatChunk> consumer = inv.getArgument(3);
            consumer.accept(chunk("c1", 123, "m", null));   // 首字节已到
            throw new UpstreamCallException(new Failure(
                    FailureType.TIMEOUT_IDLE, "流式空闲超时", -1, false, -1));
        });

        assertThatThrownBy(() -> proxy.streamChain(
                List.of(CANDIDATE_A, CANDIDATE_B), REQUEST, "r1", Map.of(), c -> {
                }))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> {
                    GatewayException g = (GatewayException) e;
                    assertThat(g.getStatus()).isEqualTo(502);
                    assertThat(g.getType()).isEqualTo("stream_interrupted");
                });
        verify(connector).stream(any(), any(), any(), any());  // 只尝试了第一个候选
    }

    /** 客户端断开：直接结束（取消由连接器内部完成），无 failure 指标、不换候选 */
    @Test
    void clientDisconnect_shouldNotCountAsFailure() throws Exception {
        when(connector.stream(any(), any(), any(), any())).thenAnswer(inv -> {
            Consumer<ChatChunk> consumer = inv.getArgument(3);
            consumer.accept(chunk("c1", 123, "m", null));
            throw new ClientDisconnectedException(new RuntimeException("客户端断开"));
        });

        proxy.streamChain(List.of(CANDIDATE_A, CANDIDATE_B), REQUEST, "r1", Map.of(), c -> {
        });

        verify(metrics).streamCancelled("qwen", "mock-a:qwen-large");
        verify(metrics, never()).failure(any(), any());  // 断连不算失败
        verify(connector).stream(any(), any(), any(), any());  // 只尝试了第一个候选
    }
}
