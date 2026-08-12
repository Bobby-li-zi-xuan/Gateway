package com.aigateway.mock.controller;

import com.aigateway.mock.model.ModelRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 动态调整 Mock 模型行为（故障注入/延迟/健康开关），用于演示网关容错。
 *
 * 示例：
 *   POST /mock/behavior {"errorRate": 1.0}                     → 100% 失败，演示网关失败切换
 *   POST /mock/behavior {"errorStatus": 500}                   → 失败时返回 500（演示降级）
 *   POST /mock/behavior {"errorStatus": 429, "retryAfterSeconds": 2} → 429 + Retry-After（演示重试）
 *   POST /mock/behavior {"streamFailAfterChunks": 3}           → 流式发 3 个 chunk 后断开（演示中断）
 *   POST /mock/behavior {"streamStallAfterChunks": 2, "streamStallMs": 30000} → 流式停顿（演示空闲超时）
 *   POST /mock/behavior {"streamErrorAfterChunks": 2}          → 流式发 2 个 chunk 后发错误事件
 *   POST /mock/behavior {"latencyMs": 5000}                    → 拉长延迟，观察超时/排队
 */
@RestController
@RequestMapping("/mock")
public class BehaviorController {

    private final ModelRegistry registry;

    public BehaviorController(ModelRegistry registry) {
        this.registry = registry;
    }

    /**
     * 更新行为参数：请求体里的每个字段都是"可选开关"，只更新出现的字段。
     * 注意：ModelProfile 是 record（不可变），每次修改都生成新对象替换引用。
     */
    @PostMapping("/behavior")
    public Map<String, Object> setBehavior(@RequestBody Map<String, Object> params) {
        if (params.containsKey("errorRate")) {
            double rate = ((Number) params.get("errorRate")).doubleValue();
            registry.setErrorRate(rate);
        }
        if (params.containsKey("latencyMs")) {
            long ms = ((Number) params.get("latencyMs")).longValue();
            registry.setLatencyMs(ms);
        }
        if (params.containsKey("activeRequests")) {
            int count = ((Number) params.get("activeRequests")).intValue();
            registry.setActiveRequests(count);
        }
        if (params.containsKey("health")) {
            registry.setHealthy(Boolean.parseBoolean(params.get("health").toString()));
        }
        // ── V3 新增：故障注入参数（可选，只更新出现的字段）──
        if (params.containsKey("errorStatus")) {
            int status = ((Number) params.get("errorStatus")).intValue();
            registry.setErrorStatus(status);
        }
        if (params.containsKey("retryAfterSeconds")) {
            int seconds = ((Number) params.get("retryAfterSeconds")).intValue();
            registry.setRetryAfterSeconds(seconds);
        }
        if (params.containsKey("streamFailAfterChunks")) {
            int chunks = ((Number) params.get("streamFailAfterChunks")).intValue();
            registry.setStreamFailAfterChunks(chunks);
        }
        if (params.containsKey("streamStallAfterChunks")) {
            int chunks = ((Number) params.get("streamStallAfterChunks")).intValue();
            registry.setStreamStallAfterChunks(chunks);
        }
        if (params.containsKey("streamStallMs")) {
            long ms = ((Number) params.get("streamStallMs")).longValue();
            registry.setStreamStallMs(ms);
        }
        if (params.containsKey("streamErrorAfterChunks")) {
            int chunks = ((Number) params.get("streamErrorAfterChunks")).intValue();
            registry.setStreamErrorAfterChunks(chunks);
        }

        // 返回当前生效的参数，方便确认修改结果
        return Map.of(
                "status", "OK",
                "current", Map.of(
                        "model", registry.getProfile().name(),
                        "latencyMs", registry.getProfile().baseLatencyMs(),
                        "errorRate", registry.getProfile().errorRate(),
                        "health", registry.isHealthy()
                )
        );
    }

    /** 查询当前状态（GET /mock/behavior） */
    @GetMapping("/behavior")
    public Map<String, Object> getBehavior() {
        Map<String, Object> result = new HashMap<>(Map.of(
                "model", registry.getProfile().name(),
                "latencyMs", registry.getProfile().baseLatencyMs(),
                "errorRate", registry.getProfile().errorRate(),
                "health", registry.isHealthy(),
                "activeRequests", registry.getCurrentRequests(),
                "responseStyle", registry.getProfile().responseStyle()
        ));
        // V3：返回故障注入参数（演示 C 用 activeRequests 回 0 断言无泄漏）
        var p = registry.getProfile();
        result.put("errorStatus", p.errorStatus());
        result.put("retryAfterSeconds", p.retryAfterSeconds());
        result.put("streamFailAfterChunks", p.streamFailAfterChunks());
        result.put("streamStallAfterChunks", p.streamStallAfterChunks());
        result.put("streamStallMs", p.streamStallMs());
        result.put("streamErrorAfterChunks", p.streamErrorAfterChunks());
        return result;
    }
}
