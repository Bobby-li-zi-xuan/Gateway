package com.aigateway.mock.controller;

import com.aigateway.mock.model.ModelProfile;
import com.aigateway.mock.model.ModelRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检测端点：网关 HealthChecker 定时拉取。
 * 状态由 /mock/behavior 的健康开关控制（默认 UP）。
 */
@RestController
public class HealthController {

    private final ModelRegistry registry;

    public HealthController(ModelRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        ModelProfile p = registry.getProfile();
        return Map.of(
                "status", registry.isHealthy() ? "UP" : "DOWN",
                "activeRequests", registry.getCurrentRequests(),
                "queueSize", p.queueSize(),
                "gpuUsage", p.gpuUsage(),
                "memoryUsage", p.memoryUsage()
        );
    }
}
