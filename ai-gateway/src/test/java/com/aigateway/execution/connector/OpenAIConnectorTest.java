package com.aigateway.execution.connector;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.Channel;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.execution.classify.FailureClassifier;
import com.aigateway.execution.model.ExecutionPolicies;
import com.aigateway.execution.model.Failure;
import com.aigateway.execution.model.FailureType;
import com.aigateway.execution.model.UpstreamCallException;
import com.aigateway.infra.config.SecretResolver;
import com.aigateway.infra.http.HttpClientFactory;
import com.aigateway.observability.GatewayMetrics;
import com.aigateway.state.registry.ModelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 连接器单测（V3 版）：SSE 逐行解析 + 超时参数化 + 结构化失败分类。
 *
 * ⚠️ stream 用例在 H2 手敲前 @Disabled：连接器内部 new TimeoutGuard(...) 后调用
 * scheduleOnce 会抛 TODO 异常（计划预期的"未完成前直接报错"），H2 完成后启用即可。
 * complete 用例不依赖 TimeoutGuard，已完整覆盖。
 */
class OpenAIConnectorTest {

    private static final String CHUNK_JSON =
            "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":123,"
            + "\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"},"
            + "\"finish_reason\":null}]}";

    private static final ModelInstance INSTANCE = new ModelInstance(
            "mock-a:qwen-large", "qwen", "mock-a", "qwen-large", 1, null,
            0.001, 0.002, 0.9, 1000L);

    private static final ChatRequest REQUEST = new ChatRequest(
            "qwen", List.of(new ChatRequest.Message("user", "hi")), true, null, null);

    private static final ExecutionPolicies POLICIES = new ExecutionPolicies(
            60_000,
            com.aigateway.execution.model.TimeoutPolicy.defaults(),
            new com.aigateway.execution.model.RetryPolicy(1, true, true, 5_000,
                    100, 2_000, 0.2, java.util.Set.of(429, 500, 502, 503, 504)),
            new com.aigateway.execution.model.CircuitBreakerConfig(100, 5, 0.5,
                    10_000, 0.5, 30_000, 1),
            new com.aigateway.execution.model.CooldownConfig(5, 30_000, 300_000, true));

    /** 构造连接器：HttpClient 用 mock（clientFor 返回 mock 客户端），其余用真实组件 */
    private OpenAIConnector newConnector(HttpClient httpClient, ModelRegistry registry) {
        Channel channel = new Channel("mock-a", "openai-compatible",
                "http://localhost:8001", "", 1);
        when(registry.findChannel("mock-a")).thenReturn(Optional.of(channel));
        HttpClientFactory factory = mock(HttpClientFactory.class);
        when(factory.clientFor(anyLong())).thenReturn(httpClient);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        return new OpenAIConnector(factory, registry, new SecretResolver(), new ObjectMapper(),
                new FailureClassifier(), mock(GatewayMetrics.class), scheduler);
    }

    private ModelRegistry newRegistry() {
        return mock(ModelRegistry.class);
    }

    private InputStream sseStream(String... lines) {
        return new ByteArrayInputStream(String.join("\n\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    /** mock HttpClient.send 返回一个指定 statusCode + body 的响应 */
    @SuppressWarnings("unchecked")
    private HttpResponse<InputStream> stubStream(HttpClient client, int status, String... lines)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(sseStream(lines));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        return response;
    }

    // ══ 非流式（不依赖 H2，已可用）══

    private static java.net.http.HttpHeaders noHeaders() {
        return java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void complete_non2xx_shouldThrowStructuredFailureWithStatus() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(response.body()).thenReturn("internal error");
        when(response.headers()).thenReturn(noHeaders());
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        assertThatThrownBy(() -> newConnector(client, newRegistry())
                .complete(INSTANCE, REQUEST, POLICIES))
                .isInstanceOf(UpstreamCallException.class)
                .satisfies(e -> {
                    Failure f = ((UpstreamCallException) e).failure();
                    assertThat(f.type()).isEqualTo(FailureType.HTTP_5XX);
                    assertThat(f.retryable()).isTrue();       // 500 在 retryableStatuses 中
                    assertThat(f.upstreamStatus()).isEqualTo(500);
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void complete_429_shouldCarryRetryAfterMillis() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(429);
        when(response.body()).thenReturn("rate limited");
        java.net.http.HttpHeaders headers = java.net.http.HttpHeaders.of(
                java.util.Map.of("Retry-After", List.of("2")), (a, b) -> true);
        when(response.headers()).thenReturn(headers);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        assertThatThrownBy(() -> newConnector(client, newRegistry())
                .complete(INSTANCE, REQUEST, POLICIES))
                .isInstanceOf(UpstreamCallException.class)
                .satisfies(e -> {
                    Failure f = ((UpstreamCallException) e).failure();
                    assertThat(f.type()).isEqualTo(FailureType.HTTP_429);
                    assertThat(f.retryAfterMs()).isEqualTo(2_000L);  // Retry-After: 2 秒
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void complete_connectionFailure_shouldClassifyConnection() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new ConnectException("Connection refused"));

        assertThatThrownBy(() -> newConnector(client, newRegistry())
                .complete(INSTANCE, REQUEST, POLICIES))
                .isInstanceOf(UpstreamCallException.class)
                .satisfies(e -> {
                    Failure f = ((UpstreamCallException) e).failure();
                    assertThat(f.type()).isEqualTo(FailureType.CONNECTION);
                    assertThat(f.retryable()).isTrue();       // 连接失败允许同实例重试一次
                });
    }

    // ══ 流式（H2 手敲后启用）══

    @Test
    @Disabled("H2 未手敲：TimeoutGuard.scheduleOnce 抛 TODO 异常，H2 完成后启用本组用例")
    void stream_shouldParseDataLinesAndIgnoreGarbage() throws Exception {
        HttpClient client = mock(HttpClient.class);
        stubStream(client, 200,
                "data: " + CHUNK_JSON,
                "data: [DONE]",
                "random garbage line",
                "data: " + CHUNK_JSON);

        List<ChatChunk> chunks = new ArrayList<>();
        newConnector(client, newRegistry())
                .stream(INSTANCE, REQUEST, POLICIES, chunks::add);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).choices().get(0).delta().content()).isEqualTo("你好");
        assertThat(chunks.get(0).object()).isEqualTo("chat.completion.chunk");
    }

    @Test
    @Disabled("H2 未手敲：同上")
    void stream_badJson_shouldThrowBadUpstreamSse() throws Exception {
        HttpClient client = mock(HttpClient.class);
        stubStream(client, 200, "data: {not json}");

        assertThatThrownBy(() -> newConnector(client, newRegistry())
                .stream(INSTANCE, REQUEST, POLICIES, c -> {
                }))
                .isInstanceOf(UpstreamCallException.class)
                .satisfies(e -> {
                    Failure f = ((UpstreamCallException) e).failure();
                    assertThat(f.type()).isEqualTo(FailureType.BAD_SSE);
                });
    }

    @Test
    @Disabled("H2 未手敲：同上")
    void stream_non2xx_shouldThrowUpstreamError() throws Exception {
        HttpClient client = mock(HttpClient.class);
        stubStream(client, 500, "internal error");

        assertThatThrownBy(() -> newConnector(client, newRegistry())
                .stream(INSTANCE, REQUEST, POLICIES, c -> {
                }))
                .isInstanceOf(UpstreamCallException.class)
                .satisfies(e -> {
                    Failure f = ((UpstreamCallException) e).failure();
                    assertThat(f.type()).isEqualTo(FailureType.HTTP_5XX);
                });
    }
}
