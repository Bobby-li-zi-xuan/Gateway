package com.aigateway.mock.controller;

import com.aigateway.mock.model.ModelProfile;
import com.aigateway.mock.model.ModelRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检测端点：网关 HealthChecker 定时拉取。
 * 状态由 /mock/behavior 的健康开关控制（默认 UP）。
 *
 * 学习要点：网关探活只认 200 + status=UP；这里额外返回活跃请求数、
 * 队列/GPU/内存占用等“运行时画像”，为后续版本（V2 调度）预留观测数据。
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
                "status", registry.isHealthy() ? "UP" : "DOWN", // 健康开关
                "activeRequests", registry.getCurrentRequests(), // 实时活跃请求数
                "queueSize", p.queueSize(),                      // 模拟排队长度
                "gpuUsage", p.gpuUsage(),                        // 模拟 GPU 使用率
                "memoryUsage", p.memoryUsage()                   // 模拟显存使用率
        );
    }
}
