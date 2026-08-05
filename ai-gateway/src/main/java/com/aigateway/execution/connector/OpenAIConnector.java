package com.aigateway.execution.connector;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.api.dto.ChatCompletion;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.Channel;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.infra.config.SecretResolver;
import com.aigateway.state.registry.ModelRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * OpenAI 兼容上游连接器。
 *
 * 🖊 手敲 H3：流式部分（stream / parseSseLine）尚未实现，
 * 请按《版本1-详细实施计划》第 10 节补全（见下方 TODO）。
 */
@Component
public class OpenAIConnector {

    private final WebClient webClient;
    private final ModelRegistry registry;
    private final SecretResolver secretResolver;

    public OpenAIConnector(WebClient webClient,
                           ModelRegistry registry,
                           SecretResolver secretResolver) {
        this.webClient = webClient;
        this.registry = registry;
        this.secretResolver = secretResolver;
    }

    /** 非流式调用 */
    public Mono<ChatCompletion> complete(ModelInstance instance, ChatRequest request) {
        return webClient.post()
                .uri(channelBaseUrl(instance) + "/v1/chat/completions")
                .headers(h -> applyAuth(h, instance))
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError,
                        resp -> resp.bodyToMono(String.class)
                                .map(body -> new GatewayException(502, "upstream_error",
                                        "上游返回 " + resp.statusCode() + ": " + body)))
                .bodyToMono(ChatCompletion.class);
    }

    /**
     * 流式调用。
     *
     * TODO H3（手敲）：按《版本1-详细实施计划》第 10 节实现：
     * 1) POST channelBaseUrl(instance) + "/v1/chat/completions"
     * 2) accept(MediaType.TEXT_EVENT_STREAM)，bodyValue(request.withStream(true))
     * 3) bodyToFlux(String.class) 逐行收 SSE，flatMap(line -> parseSseLine(line))
     */
    public Flux<ChatChunk> stream(ModelInstance instance, ChatRequest request) {
        return webClient.post()
                .uri(instance.endpoint() + "/v1/chat/completions")
                .headers(h -> applyAuth(h, instance))
                .accepty(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(new ChatRequest(request.model(), request.messages(),
                                true, request.temperature(), request.maxTokens()))
                .retrieve()
                .bodyToFlux(String.class)
                .flatMap(line -> parseSseLine(line, request.model()));
    }

    /**
     * SSE 行解析。
     *
     * TODO H3（手敲）：按《版本1-详细实施计划》第 10 节实现：
     * - "data: {...}" → 解析为 ChatChunk（需要时注入 ObjectMapper）
     * - "data: [DONE]" → 空 Flux（结束）
     * - 其他行（注释/空行）→ 忽略
     * - JSON 解析失败 → GatewayException(502, "bad_upstream_sse", ...)
     */
    private Flux<ChatChunk> parseSseLine(String line) {
        throw new UnsupportedOperationException(
                "H3 未实现：请手敲 parseSseLine()（见详细实施计划第 10 节）");
    }

    private void applyAuth(HttpHeaders headers, ModelInstance instance) {
        registry.findChannel(instance.channelId())
                .filter(Channel::needAuth)
                .ifPresent(ch -> secretResolver.resolve(ch.credentialsRef())
                        .ifPresent(headers::setBearerAuth));
    }

    private String channelBaseUrl(ModelInstance instance) {
        return registry.findChannel(instance.channelId())
                .map(Channel::baseUrl)
                .orElseThrow(() -> new GatewayException(500, "invalid_config",
                        "渠道不存在: " + instance.channelId()));
    }
}
