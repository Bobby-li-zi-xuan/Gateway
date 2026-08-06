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
 *
 * 学习要点：
 * - 非流式：模拟延迟后一次性返回完整 JSON；
 * - 流式：按 responseStyle 把内容切成 token，用虚拟线程配合 SseEmitter
 *   逐个推送增量 chunk（Spring 会自动按 data: 帧格式输出，网关端逐行读取即可）；
 * - shouldFail() 用于故障注入演示“网关失败切换”。
 */
@RestController
public class ChatController {

    private final ModelRegistry registry; // 当前模型画像 + 健康/请求计数
    private final ResponseGenerator gen;  // 内容生成 + 延迟/故障模拟

    public ChatController(ModelRegistry registry, ResponseGenerator gen) {
        this.registry = registry;
        this.gen = gen;
    }

    /** 非流式推理：POST /v1/chat/completions（不带 stream=true） */
    @PostMapping("/v1/chat/completions")
    public Map<String, Object> chat(@RequestBody Map<String, Object> request) {
        ModelProfile profile = registry.getProfile();
        String requestId = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8);
        registry.requestStarted(); // 计入活跃请求数（/health 会展示）

        // 1. 生成内容 + 计算模拟延迟
        String content = gen.generate(profile);
        long latencyMs = gen.simulateLatency(profile, content);

        // 2. 故障注入：命中错误率时，睡满延迟再抛异常（模拟“上游处理中挂掉”）
        if (gen.shouldFail(profile)) {
            sleep(latencyMs);
            registry.requestFinished();
            throw new RuntimeException("Mock model internal error");
        }

        // 3. 正常路径：模拟推理延迟后返回 OpenAI 非流式格式
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
                        "completion_tokens", content.length() / 3, // 粗略估算：汉字约 1 token/字
                        "total_tokens", estimateTokens(request) + content.length() / 3
                )
        );
    }

    /**
     * 流式推理：POST /v1/chat/completions?stream=true。
     * Spring 用 params = "stream=true" 区分流式/非流式两个 handler。
     *
     * 流程：生成内容 → 切成 token → 虚拟线程里逐 token send chunk →
     * 最后发一个 finish_reason=stop 的结束 chunk → complete()。
     */
    @PostMapping(value = "/v1/chat/completions", params = "stream=true")
    public SseEmitter chatStream(@RequestBody Map<String, Object> request) {
        ModelProfile profile = registry.getProfile();
        String requestId = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8);
        String content = gen.generate(profile);

        // 故障注入：流式在“首字节前”失败，正好演示网关 H3/H2 的首字节前切换
        if (gen.shouldFail(profile)) {
            throw new RuntimeException("Mock model internal error");
        }

        String[] tokens = splitIntoTokens(content, profile.responseStyle());
        SseEmitter emitter = new SseEmitter(0L); // 0 = 不设超时，流式时长由内容长度决定
        // 每个流式请求一个虚拟线程，逐 token 推送
        Thread.ofVirtual().name("mock-sse-" + requestId).start(() -> {
            registry.requestStarted();
            try {
                for (String token : tokens) {
                    emitter.send(buildChunk(requestId, profile.name(), token, false));
                    Thread.sleep((long) profile.streamingDelayMs()); // 模拟生成速率
                }
                // 结束 chunk：delta 为空 + finish_reason=stop
                emitter.send(buildChunk(requestId, profile.name(), "", true));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e); // 客户端断开等异常 → 错误结束
            } finally {
                registry.requestFinished();
            }
        });
        return emitter;
    }

    /** 让出当前线程指定毫秒；被中断时恢复中断标记（线程池最佳实践） */
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 构造一个 OpenAI 流式 chunk：
     * delta 是增量文本（结束 chunk 为 null 值空 map），finish_reason 只在结束时为 "stop"。
     */
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

    /**
     * 按语言特性切分 Token，模拟真实 SSE 推送节奏：
     * - CODE 风格按换行切（代码块一行一行出）；
     * - 其它风格按中文标点/逗号切（一句话一个 chunk）。
     */
    private String[] splitIntoTokens(String content, String style) {
        if ("CODE".equals(style)) {
            return content.split("(?<=\n)");
        }
        return content.split("(?<=[。，,])");
    }

    /** 简单 Token 估算：汉字按 3 字/token 粗略折算，至少返回 1，用于 usage 字段 */
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
