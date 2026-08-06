package com.aigateway.mock.model;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock 模型运行时状态：行为参数 + 健康开关 + 当前请求数。
 *
 * 学习要点：
 * - {@code @Value("${mock.model:qwen-small}")} 注入启动参数 --mock.model，
 *   缺省回退到 qwen-small；
 * - profile 是 volatile 引用：BehaviorController 写、请求线程读，保证可见性；
 * - healthy 用 volatile 布尔：健康开关即时生效；
 * - requestCounter 用 AtomicInteger：并发请求下安全地增/减活跃数。
 */
@Component
public class ModelRegistry {

    /** 当前 mock 实例模拟的模型名（启动参数选择） */
    @Value("${mock.model:qwen-small}")
    private String modelName;

    /** 当前生效的模型画像（不可变对象，替换引用方式更新） */
    private volatile ModelProfile profile;

    /** 健康开关：/mock/behavior 可动态调整，/health 读取 */
    private volatile boolean healthy = true;

    /** 当前活跃请求计数，用于 /health 动态返回 */
    private final AtomicInteger requestCounter = new AtomicInteger(0);

    /** 启动时根据 --mock.model 选择对应的模型画像 */
    @PostConstruct
    public void init() {
        this.profile = switch (modelName) {
            // qwen-large：分析型，延迟高、流式慢
            case "qwen-large" -> new ModelProfile(
                    "qwen-large", "Qwen2.5-72B", 2000L, 0.0,
                    50.0, 8, 2, 0.55, 0.60,
                    "ANALYSIS",
                    Map.of("codingAbility", "MEDIUM", "reasoningLevel", "HIGH")
            );
            // deepseek-code：代码型，延迟低、流式快
            case "deepseek-code" -> new ModelProfile(
                    "deepseek-code", "DeepSeek-Coder-V2", 500L, 0.0,
                    15.0, 12, 1, 0.45, 0.50,
                    "CODE",
                    Map.of("codingAbility", "MAX", "reasoningLevel", "MEDIUM")
            );
            // 默认 qwen-small：聊天型，最快
            default -> new ModelProfile(
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
    // 每次都是“基于旧画像生成新画像再替换引用”，保持 record 不可变性

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

    /** 请求开始：活跃数 +1（聊天接口调用） */
    public void requestStarted() { requestCounter.incrementAndGet(); }

    /** 请求结束：活跃数 -1 */
    public void requestFinished() { requestCounter.decrementAndGet(); }

    public int getCurrentRequests() {
        return requestCounter.get();
    }
}
