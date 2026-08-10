package com.aigateway.mock.controller;

import com.aigateway.mock.model.ModelProfile;
import com.aigateway.mock.model.ModelRegistry;
import com.aigateway.mock.service.ResponseGenerator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mock 聊天接口：模拟 OpenAI 兼容的非流式与流式（SSE）响应。
 *
 * 学习要点：
 * - OpenAI 约定 stream 是“请求体字段”（不是查询参数），所以这里用
 *   “读 body 里的 stream 字段”分流，而不是 @PostMapping(params=...)；
 *   这样与网关 H3（body 带 stream=true）以及真实客户端语义保持一致；
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

    /**
     * 统一入口：POST /v1/chat/completions。
     * 根据请求体里的 stream 字段分流：true → SSE 流式；否则非流式 JSON。
     */
    @PostMapping("/v1/chat/completions")
    public Object chat(@RequestBody Map<String, Object> request) {
        if (Boolean.TRUE.equals(request.get("stream"))) {
            return chatStream(request);
        }
        return chatOnce(request);
    }

    /** 非流式推理：模拟延迟后返回 OpenAI 非流式格式 */
    private Map<String, Object> chatOnce(Map<String, Object> request) {
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
                        "completion_tokens", estimateTextTokens(content),
                        "total_tokens", estimateTokens(request) + estimateTextTokens(content)
                )
        );
    }

    /**
     * 流式推理。
     *
     * 流程：生成内容 → 切成 token → 虚拟线程里逐 token send chunk →
     * 最后发一个 finish_reason=stop 的结束 chunk → complete()。
     */
    private SseEmitter chatStream(Map<String, Object> request) {
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
     * - delta 是增量文本（结束 chunk 为 null 值空 map）；
     * - finish_reason 只在结束 chunk 出现（OpenAI 协议中间 chunk 为 null/缺省）。
     * 注意：Map.of 不允许 null 值，所以这里用 HashMap 按需放入 finish_reason。
     */
    private Map<String, Object> buildChunk(String id, String model, String delta, boolean isLast) {
        Map<String, Object> choice = new HashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta.isEmpty() ? Map.of() : Map.of("content", delta));
        if (isLast) {
            choice.put("finish_reason", "stop");
        }
        return Map.of(
                "id", id,
                "object", "chat.completion.chunk",
                "created", System.currentTimeMillis() / 1000,
                "model", model,
                "choices", List.of(choice)
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

    /** 输入 Token 估算：中文约 1 字/token、其他约 4 字符/token（口径与 ai-gateway CapabilityFilter 一致），至少返回 1，用于 usage 字段 */
    private int estimateTokens(Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        var messages = (List<Map<String, Object>>) request.get("messages");
        if (messages == null) return 0;

        int total = 0;
        for (var msg : messages) {
            Object content = msg.get("content");
            if (content instanceof String s) {
                total += estimateTextTokens(s);
            }
        }
        return Math.max(total, 1);
    }

    /** 文本 → token 近似：CJK 字符约 1 字/token，其余约 4 字符/token */
    private static int estimateTextTokens(String text) {
        int cjk = 0, other = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) cjk++;
            else other++;
        }
        return cjk + (other + 3) / 4;
    }
}
