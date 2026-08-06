package com.aigateway.mock.controller;

import com.aigateway.mock.model.ModelRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 动态调整 Mock 模型行为（故障注入/延迟/健康开关），用于演示网关容错。
 *
 * 示例：
 *   POST /mock/behavior {"errorRate": 1.0}     → 100% 失败，演示网关失败切换
 *   POST /mock/behavior {"health": false}      → /health 返回 DOWN，演示健康剔除
 *   POST /mock/behavior {"latencyMs": 5000}    → 拉长延迟，观察超时/排队
 */
@RestController
@RequestMapping("/mock")
public class BehaviorController {

    private final ModelRegistry registry;

    public BehaviorController(ModelRegistry registry) {
        this.registry = registry;
    }

    /**
     * 更新行为参数：请求体里的每个字段都是“可选开关”，只更新出现的字段。
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
        return Map.of(
                "model", registry.getProfile().name(),
                "latencyMs", registry.getProfile().baseLatencyMs(),
                "errorRate", registry.getProfile().errorRate(),
                "health", registry.isHealthy(),
                "activeRequests", registry.getCurrentRequests(),
                "responseStyle", registry.getProfile().responseStyle()
        );
    }
}
