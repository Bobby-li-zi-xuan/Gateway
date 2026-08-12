package com.aigateway.execution.model;

/**
 * 失败分类：所有上游失败先归类，再决定"能否重试 / 能否降级"。
 *
 * 核心心法（学习版 4.0）：失败不可怕，可怕的是不知道什么失败能重试、什么只能放弃。
 * 决策只看类型，不按异常消息猜。
 */
public enum FailureType {
    CONNECTION,              // 连接失败：DNS/拒绝/重置/半关闭（唯一允许同实例重试的类型）
    TIMEOUT_CONNECT,         // 连接超时
    TIMEOUT_REQUEST,         // 非流式完整响应超时
    TIMEOUT_FIRST_BYTE,      // 流式首字节超时
    TIMEOUT_IDLE,            // 流式空闲超时
    TIMEOUT_TOTAL,           // 整条降级链总时长超时
    HTTP_429,                // 上游限流（可遵循 Retry-After）
    HTTP_5XX,                // 上游 5xx（可换候选）
    HTTP_OTHER,              // 其它非 2xx（一般不可重试）
    BAD_SSE,                 // SSE 解析失败
    STREAM_INTERRUPTED,      // 流式首字节后中断
    CANCELLED,               // 客户端断开 / 主动取消（不是失败，不计数）
    UNKNOWN
}
