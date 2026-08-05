package com.aigateway.execution.connector;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.api.dto.ChatCompletion;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.Channel;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.infra.config.SecretResolver;
import com.aigateway.state.registry.ModelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * OpenAI 兼容上游连接器（阻塞式，JDK HttpClient）。
 *
 * 🖊 手敲 H3：流式部分（stream / parseSseLine）尚未实现，
 * 请按《版本1-详细实施计划》第 10 节补全（见下方 TODO）。
 */
@Component
public class OpenAIConnector {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final ModelRegistry registry;
    private final SecretResolver secretResolver;
    private final ObjectMapper objectMapper;

    public OpenAIConnector(HttpClient httpClient,
                           ModelRegistry registry,
                           SecretResolver secretResolver,
                           ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.registry = registry;
        this.secretResolver = secretResolver;
        this.objectMapper = objectMapper;
    }

    /** 非流式调用（脚手架，已实现）。 */
    public ChatCompletion complete(ModelInstance instance, ChatRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(channelBaseUrl(instance) + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(request)));
        applyAuth(builder, instance);
        try {
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new GatewayException(502, "upstream_error",
                        "上游返回 " + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readValue(response.body(), ChatCompletion.class);
        } catch (GatewayException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(502, "upstream_failed", "调用上游失败: " + e.getMessage(), e);
        }
    }

    /**
     * 流式调用。
     *
     * TODO H3（手敲）：按《版本1-详细实施计划》第 10 节实现：
     * 1) HttpRequest POST channelBaseUrl(instance) + "/v1/chat/completions"
     *    header Accept: text/event-stream，body 为 request.withStream(true) 的 JSON
     * 2) httpClient.send(..., BodyHandlers.ofInputStream())，非 2xx 抛 GatewayException(502, ...)
     * 3) 用 BufferedReader 逐行读取，parseSseLine(line) 非 null 时 consumer.accept(chunk)
     * 4) IOException/InterruptedException → GatewayException(502, "upstream_failed", ...)
     */
    public void stream(ModelInstance instance, ChatRequest request, Consumer<ChatChunk> consumer) {
        throw new UnsupportedOperationException(
                "H3 未实现：请手敲 stream()（见详细实施计划第 10 节）");
    }

    /**
     * SSE 行解析。
     *
     * TODO H3（手敲）：按《版本1-详细实施计划》第 10 节实现：
     * - "data: {...}" → objectMapper.readValue(payload, ChatChunk.class)
     * - "data: [DONE]" 或非 data 行 → 返回 null
     * - JSON 解析失败 → GatewayException(502, "bad_upstream_sse", ...)
     */
    private ChatChunk parseSseLine(String line) {
        throw new UnsupportedOperationException(
                "H3 未实现：请手敲 parseSseLine()（见详细实施计划第 10 节）");
    }

    private void applyAuth(HttpRequest.Builder builder, ModelInstance instance) {
        registry.findChannel(instance.channelId())
                .filter(Channel::needAuth)
                .ifPresent(ch -> secretResolver.resolve(ch.credentialsRef())
                        .ifPresent(token -> builder.header("Authorization", "Bearer " + token)));
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new GatewayException(500, "internal_error", "请求序列化失败", e);
        }
    }

    private String channelBaseUrl(ModelInstance instance) {
        return registry.findChannel(instance.channelId())
                .map(Channel::baseUrl)
                .orElseThrow(() -> new GatewayException(500, "invalid_config",
                        "渠道不存在: " + instance.channelId()));
    }
}
