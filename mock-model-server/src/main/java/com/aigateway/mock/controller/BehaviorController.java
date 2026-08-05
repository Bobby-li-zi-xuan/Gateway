package com.aigateway.mock.controller;

import com.aigateway.mock.model.ModelRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 动态调整 Mock 模型行为（故障注入/延迟/健康开关），用于演示。
 */
@RestController
@RequestMapping("/mock")
public class BehaviorController {

    private final ModelRegistry registry;

    public BehaviorController(ModelRegistry registry) {
        this.registry = registry;
    }

    /**
     * POST /mock/behavior
     * {"latencyMs": 5000, "errorRate": 0.8, "health": false, "activeRequests": 15}
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

    /** 查询当前状态 */
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
