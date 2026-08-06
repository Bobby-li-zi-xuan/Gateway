package com.aigateway.execution.connector;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.Channel;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.infra.config.SecretResolver;
import com.aigateway.state.registry.ModelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 连接器单测（计划第 16.1 节）：SSE 逐行解析。
 *
 * 覆盖：data: {...} 解析为 ChatChunk；[DONE] 结束；垃圾行忽略；
 *       JSON 解析失败抛 bad_upstream_sse；非 2xx 抛 upstream_error。
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

    /** 构造连接器：HttpClient 用 mock，其余用真实组件 */
    private OpenAIConnector newConnector(HttpClient httpClient, ModelRegistry registry) {
        Channel channel = new Channel("mock-a", "openai-compatible",
                "http://localhost:8001", "", 1);
        when(registry.findChannel("mock-a")).thenReturn(Optional.of(channel));
        return new OpenAIConnector(httpClient, registry, new SecretResolver(), new ObjectMapper());
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

    @Test
    void stream_shouldParseDataLinesAndIgnoreGarbage() throws Exception {
        HttpClient client = mock(HttpClient.class);
        stubStream(client, 200,
                "data: " + CHUNK_JSON,
                "data: [DONE]",
                "random garbage line",
                "data: " + CHUNK_JSON);

        List<ChatChunk> chunks = new ArrayList<>();
        newConnector(client, newRegistry())
                .stream(INSTANCE, REQUEST, chunks::add);

        // 两个有效 data 行被解析；[DONE] 和垃圾行被忽略
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).choices().get(0).delta().content()).isEqualTo("你好");
        assertThat(chunks.get(0).object()).isEqualTo("chat.completion.chunk");
    }

    @Test
    void stream_badJson_shouldThrowBadUpstreamSse() throws Exception {
        HttpClient client = mock(HttpClient.class);
        stubStream(client, 200, "data: {not json}");

        assertThatThrownBy(() -> newConnector(client, newRegistry())
                .stream(INSTANCE, REQUEST, c -> {
                }))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> {
                    assertThat(((GatewayException) e).getStatus()).isEqualTo(502);
                    assertThat(((GatewayException) e).getType()).isEqualTo("bad_upstream_sse");
                });
    }

    @Test
    void stream_non2xx_shouldThrowUpstreamError() throws Exception {
        HttpClient client = mock(HttpClient.class);
        stubStream(client, 500, "internal error");

        assertThatThrownBy(() -> newConnector(client, newRegistry())
                .stream(INSTANCE, REQUEST, c -> {
                }))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> {
                    assertThat(((GatewayException) e).getStatus()).isEqualTo(502);
                    assertThat(((GatewayException) e).getType()).isEqualTo("upstream_error");
                });
    }
}
