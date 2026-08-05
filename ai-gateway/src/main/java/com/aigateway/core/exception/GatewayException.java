package com.aigateway.core.exception;

import lombok.Getter;

/**
 * 网关统一业务异常：携带 HTTP 状态码与 OpenAI 风格错误类型。
 */
@Getter
public class GatewayException extends RuntimeException {

    private final int status;
    private final String type;

    public GatewayException(int status, String type, String message) {
        super(message);
        this.status = status;
        this.type = type;
    }

    public GatewayException(int status, String type, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.type = type;
    }
}
