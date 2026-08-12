package com.aigateway.execution.model;

/**
 * 内部执行异常：连接器与执行器之间传递"结构化失败"，
 * 不再用裸 Exception 猜类型。
 */
public class UpstreamCallException extends RuntimeException {

    private final Failure failure;

    public UpstreamCallException(Failure failure) {
        super(failure.reason());
        this.failure = failure;
    }

    public UpstreamCallException(Failure failure, Throwable cause) {
        super(failure.reason(), cause);
        this.failure = failure;
    }

    public Failure failure() { return failure; }
}
