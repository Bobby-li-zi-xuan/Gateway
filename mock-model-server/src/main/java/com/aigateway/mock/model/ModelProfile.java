package com.aigateway.mock.model;

import java.util.Map;

/**
 * 描述一个 Mock 模型的全部行为参数。
 *
 * 学习要点：
 * - record 不可变：BehaviorController 每次修改参数都会生成新实例并替换引用，
 *   保证读线程看到的是完整一致的画像（不会读到改了一半的状态）；
 * - 启动时通过 --mock.model=xxx 选择模型，其余参数由 ModelRegistry 提供默认值；
 * - V3 新增 6 个故障注入字段（错误状态码 / Retry-After / 流式中途故障 / 停顿 / 错误事件）。
 */
public record ModelProfile (
    String name,                 // 模型对外名称（如 qwen-large）
    String version,              // 版本/规格说明（如 Qwen2.5-72B）
    long baseLatencyMs,          // 基础推理延迟（毫秒，模拟）
    double errorRate,            // 错误率 0.0 - 1.0（故障注入）
    double streamingDelayMs,     // 流式模式下每个 token 之间的间隔（毫秒）
    int activeRequests,          // /health 返回的当前活跃请求数（模拟值）
    int queueSize,               // /health 返回的等待队列长度（模拟值）
    double gpuUsage,             // /health 返回的 GPU 使用率（模拟值）
    double memoryUsage,          // /health 返回的显存使用率（模拟值）
    String responseStyle,        // 响应风格：CODE / ANALYSIS / CHAT
    Map<String, Object> metadata, // 额外元数据（能力标签等，V2 调度用）
    // ── V3 新增（故障注入参数，演示网关容错）──
    int errorStatus,              // 故障注入的 HTTP 状态码（默认 500）
    int retryAfterSeconds,        // 429 时返回的 Retry-After 秒数
    int streamFailAfterChunks,    // 流式发送 N 个 chunk 后异常断开；-1 = 关闭
    int streamStallAfterChunks,   // 流式发送 N 个 chunk 后停顿；-1 = 关闭
    long streamStallMs,           // 停顿时长（毫秒）
    int streamErrorAfterChunks    // 流式发送 N 个 chunk 后发错误事件；-1 = 关闭
){}
