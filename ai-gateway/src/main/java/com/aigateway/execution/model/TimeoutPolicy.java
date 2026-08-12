package com.aigateway.execution.model;

/**
 * 分层超时配置（学习版 4.2）：四层独立超时，精确回答"卡在哪一步"。
 *
 * @param connectMs    建立 TCP 连接
 * @param requestMs    非流式完整响应
 * @param firstByteMs  流式首字节
 * @param idleMs       流式空闲（相邻 chunk 最大间隔）
 */
public record TimeoutPolicy(
        long connectMs,
        long requestMs,
        long firstByteMs,
        long idleMs
) {
    public static TimeoutPolicy defaults() {
        return new TimeoutPolicy(500, 30_000, 10_000, 30_000);
    }
}
