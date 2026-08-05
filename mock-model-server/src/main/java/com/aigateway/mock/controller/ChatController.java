package com.aigateway.mock.controller;

import com.aigateway.mock.model.ModelProfile;
import com.aigateway.mock.model.ModelRegistry;
import com.aigateway.mock.service.ResponseGenerator;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mock 聊天接口：模拟 OpenAI 兼容的非流式与流式（SSE）响应。
 */
@RestController
public class ChatController {

    private final ModelRegistry registry;
    private final ResponseGenerator gen;

    public ChatController(ModelRegistry registry, ResponseGenerator gen) {
        this.registry = registry;
        this.gen = gen;
    }

    /** 非流式推理 */
    @PostMapping("/v1/chat/completions")
    public Mono<Map<String, Object>> chat(@RequestBody Map<String, Object> request) {
        ModelProfile profile = registry.getProfile();
        String requestId = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8);
        registry.requestStarted();

        String content = gen.generate(profile);
        long latencyMs = gen.simulateLatency(profile, content);

        if (gen.shouldFail(profile)) {
            registry.requestFinished();
            return Mono.delay(Duration.ofMillis(latencyMs))
                    .then(Mono.error(new RuntimeException("Mock model internal error")));
        }

        return Mono.delay(Duration.ofMillis(latencyMs))
                .then(Mono.fromCallable(() -> {
                    registry.requestFinished();
                    return Map.<String, Object>of(
                            "id", requestId,
                            "object", "chat.completion",
                            "created", System.currentTimeMillis() / 1000,
                            "model", profile.name(),
                            "choices", List.of(Map.of(
                                    "index", 0,
                                    "message", Map.of("role", "assistant", "content", content),
                                    "finish_reason", "stop"
                            )),
                            "usage", Map.of(
                                    "prompt_tokens", estimateTokens(request),
                                    "completion_tokens", content.length() / 3,
                                    "total_tokens", estimateTokens(request) + content.length() / 3
                            )
                    );
                }));
    }

    /** 流式推理（标准 POST + SSE，按 chunk 逐段推送） */
    @PostMapping(value = "/v1/chat/completions", params = "stream=true",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> chatStream(
            @RequestBody Map<String, Object> request) {
        ModelProfile profile = registry.getProfile();
        String requestId = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8);
        String content = gen.generate(profile);
        registry.requestStarted();

        if (gen.shouldFail(profile)) {
            registry.requestFinished();
            return Flux.error(new RuntimeException("Mock model internal error"));
        }

        String[] tokens = splitIntoTokens(content, profile.responseStyle());
        return Flux.fromArray(tokens)
                .delayElements(Duration.ofMillis((long) profile.streamingDelayMs()))
                .map(token -> ServerSentEvent.builder(
                        buildChunk(requestId, profile.name(), token, false)).build())
                .concatWith(Mono.just(ServerSentEvent.builder(
                        buildChunk(requestId, profile.name(), "", true)).build()))
                .doOnComplete(registry::requestFinished);
    }

    private Map<String, Object> buildChunk(String id, String model, String delta, boolean isLast) {
        return Map.of(
                "id", id,
                "object", "chat.completion.chunk",
                "created", System.currentTimeMillis() / 1000,
                "model", model,
                "choices", List.of(Map.of(
                        "index", 0,
                        "delta", delta.isEmpty()
                                ? Map.of()
                                : Map.of("content", delta),
                        "finish_reason", isLast ? "stop" : (Object) null
                ))
        );
    }

    /** 按语言特性切分 Token，模拟真实 SSE 推送 */
    private String[] splitIntoTokens(String content, String style) {
        if ("CODE".equals(style)) {
            return content.split("(?<=\n)");
        }
        return content.split("(?<=[。，,])");
    }

    /** 简单 Token 估算：中文字符 + 英文单词 */
    private int estimateTokens(Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        var messages = (List<Map<String, Object>>) request.get("messages");
        if (messages == null) return 0;

        int total = 0;
        for (var msg : messages) {
            Object content = msg.get("content");
            if (content instanceof String s) {
                total += s.length() / 3;
            }
        }
        return Math.max(total, 1);
    }
}
