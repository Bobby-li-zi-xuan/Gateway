package com.aigateway.api.error;

import com.aigateway.core.exception.GatewayException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误体：{ type, message }，OpenAI 风格。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ApiError(String type, String message) {}

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ApiError> handleGateway(GatewayException e) {
        return ResponseEntity.status(e.getStatus())
                .body(new ApiError(e.getType(), e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleBadRequest(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError("invalid_request", "请求体不合法: " + e.getMessage()));
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiError> handleOther(Throwable e) {
        return ResponseEntity.status(500)
                .body(new ApiError("internal_error", "网关内部错误"));
    }
}
