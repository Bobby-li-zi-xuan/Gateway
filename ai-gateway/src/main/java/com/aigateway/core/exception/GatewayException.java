package com.aigateway.core.exception;

import lombok.Getter;

/**
 * 网关统一业务异常：携带 HTTP 状态码与 OpenAI 风格错误类型。
 *
 * 设计动机：
 * - 网关内部各处抛错只关心“状态码 + 类型 + 描述”，由 GlobalExceptionHandler
 *   统一转成 { type, message } 返回给客户端；
 * - 相比直接返回 ResponseEntity，用异常表达失败可以让业务代码更专注正常路径。
 *
 * 常用错误码约定（V1）：
 * 400 invalid_request / 502 upstream_failed|upstream_error / 503 no_available_model / 500 internal_error。
 */
@Getter
public class GatewayException extends RuntimeException {

    private final int status;   // 要返回给客户端的 HTTP 状态码
    private final String type;  // OpenAI 风格错误类型（见类注释）

    public GatewayException(int status, String type, String message) {
        super(message);
        this.status = status;
        this.type = type;
    }

    /** 带原始异常 cause 的构造器：保留根因，便于日志排查 */
    public GatewayException(int status, String type, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.type = type;
    }
}
