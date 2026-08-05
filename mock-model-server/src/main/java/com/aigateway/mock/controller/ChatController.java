package com.aigateway.mock.controller;

import com.aigateway.mock.model.ModelProfile;
import com.aigateway.mock.model.ModelRegistry;
import com.aigateway.mock.service.ResponseGenerator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mock 聊天接口：模拟 OpenAI 兼容的非流式与流式（SSE）响应。
 * 阻塞式 + 虚拟线程实现。
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
    public Map<String, Object> chat(@RequestBody Map<String, Object> request) {
        ModelProfile profile = registry.getProfile();
        String requestId = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8);
        registry.requestStarted();

        String content = gen.generate(profile);
        long latencyMs = gen.simulateLatency(profile, content);

        if (gen.shouldFail(profile)) {
            sleep(latencyMs);
            registry.requestFinished();
            throw new RuntimeException("Mock model internal error");
        }

        sleep(latencyMs);
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
    }

    /** 流式推理：虚拟线程按 chunk 推送 SSE */
    @PostMapping(value = "/v1/chat/completions", params = "stream=true")
    public SseEmitter chatStream(@RequestBody Map<String, Object> request) {
        ModelProfile profile = registry.getProfile();
        String requestId = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8);
        String content = gen.generate(profile);

        if (gen.shouldFail(profile)) {
            throw new RuntimeException("Mock model internal error");
        }

        String[] tokens = splitIntoTokens(content, profile.responseStyle());
        SseEmitter emitter = new SseEmitter(0L);
        Thread.ofVirtual().name("mock-sse-" + requestId).start(() -> {
            registry.requestStarted();
            try {
                for (String token : tokens) {
                    emitter.send(buildChunk(requestId, profile.name(), token, false));
                    Thread.sleep((long) profile.streamingDelayMs());
                }
                emitter.send(buildChunk(requestId, profile.name(), "", true));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            } finally {
                registry.requestFinished();
            }
        });
        return emitter;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
