package com.aigateway.execution.model;

/**
 * 流式会话句柄：连接器返回，执行器/代理可以通过 cancel() 主动取消上游连接。
 *
 * JDK HttpClient 语义：响应体 InputStream 未消费完时 close() 会取消底层交换，
 * 阻塞中的 readLine() 随即抛 IOException——这就是"客户端断开 → 取消上游"的机制。
 */
public interface StreamSession extends AutoCloseable {

    /** 关闭上游连接（幂等：多次调用只生效一次） */
    void cancel();

    /** 是否已被取消（区分"正常结束"与"被取消"，避免把取消误报为失败） */
    boolean isCancelled();

    @Override
    default void close() { cancel(); }
}
