package com.aigateway.mock.model;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock 模型运行时状态：行为参数 + 健康开关 + 当前请求数。
 */
@Component
public class ModelRegistry {

    @Value("${mock.model:qwen-small}")
    private String modelName;

    private ModelProfile profile;
    private volatile boolean healthy = true;

    // 当前请求计数，用于 /health 动态返回
    private final AtomicInteger requestCounter = new AtomicInteger(0);

    @PostConstruct
    public void init() {
        this.profile = switch (modelName) {
            case "qwen-large" -> new ModelProfile(
                    "qwen-large", "Qwen2.5-72B", 2000L, 0.0,
                    50.0, 8, 2, 0.55, 0.60,
                    "ANALYSIS",
                    Map.of("codingAbility", "MEDIUM", "reasoningLevel", "HIGH")
            );
            case "deepseek-code" -> new ModelProfile(
                    "deepseek-code", "DeepSeek-Coder-V2", 500L, 0.0,
                    15.0, 12, 1, 0.45, 0.50,
                    "CODE",
                    Map.of("codingAbility", "MAX", "reasoningLevel", "MEDIUM")
            );
            default -> new ModelProfile(  // qwen-small
                    "qwen-small", "Qwen2.5-14B", 200L, 0.0,
                    5.0, 5, 0, 0.30, 0.35,
                    "CHAT",
                    Map.of("codingAbility", "LOW", "reasoningLevel", "LOW")
            );
        };
    }

    public ModelProfile getProfile() {
        return profile;
    }

    // ---- 健康开关（/mock/behavior 可动态调整，/health 读取） ----

    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }

    public boolean isHealthy() {
        return healthy;
    }

    // ---- 以下方法支持 /mock/behavior 动态修改行为 ----

    public void setErrorRate(double rate) {
        this.profile = new ModelProfile(
                profile.name(), profile.version(), profile.baseLatencyMs(), rate,
                profile.streamingDelayMs(), profile.activeRequests(), profile.queueSize(),
                profile.gpuUsage(), profile.memoryUsage(),
                profile.responseStyle(), profile.metadata()
        );
    }

    public void setLatencyMs(long ms) {
        this.profile = new ModelProfile(
                profile.name(), profile.version(), ms, profile.errorRate(),
                profile.streamingDelayMs(), profile.activeRequests(), profile.queueSize(),
                profile.gpuUsage(), profile.memoryUsage(),
                profile.responseStyle(), profile.metadata()
        );
    }

    public void setActiveRequests(int count) {
        this.profile = new ModelProfile(
                profile.name(), profile.version(), profile.baseLatencyMs(), profile.errorRate(),
                profile.streamingDelayMs(), count, profile.queueSize(),
                profile.gpuUsage(), profile.memoryUsage(),
                profile.responseStyle(), profile.metadata()
        );
    }

    public void requestStarted() { requestCounter.incrementAndGet(); }
    public void requestFinished() { requestCounter.decrementAndGet(); }

    public int getCurrentRequests() {
        return requestCounter.get();
    }
}
