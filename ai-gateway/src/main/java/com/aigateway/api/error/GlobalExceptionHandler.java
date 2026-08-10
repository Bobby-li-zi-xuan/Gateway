package com.aigateway.api.error;

import com.aigateway.core.exception.GatewayException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：把异常统一转换成 OpenAI 风格错误体 { type, message }。
 *
 * 学习要点：
 * - {@code @RestControllerAdvice} 会拦截所有 Controller 抛出的异常，避免每个接口
 *   各自 try-catch；
 * - 处理顺序：Spring 按“最具体的异常类型”匹配，因此 GatewayException 最优先，
 *   Exception 作为最后兜底；
 * - 兜底只捕获 Exception 而不捕获 Throwable：Error（OOM、StackOverflowError 等）
 *   是致命错误，不应被吞掉转成 500 响应；
 * - 框架语义异常（如未知路径 NoResourceFoundException）按各自语义处理，
 *   而不是一律 500——Spring 默认为它们定义了正确的状态码。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 统一错误体：type 是 OpenAI 风格错误码，message 是人可读的描述 */
    public record ApiError(String type, String message) {}

    /** 业务异常：直接把异常携带的 HTTP 状态码和 type 透出 */
    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ApiError> handleGateway(GatewayException e) {
        return ResponseEntity.status(e.getStatus())
                .body(new ApiError(e.getType(), e.getMessage()));
    }

    /** 请求体 JSON 不合法（缺字段/类型错误）→ 400 invalid_request */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleBadRequest(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError("invalid_request", "请求体不合法: " + e.getMessage()));
    }

    /** 未知路径（Spring MVC 找不到静态资源/映射）→ 404 not_found */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(404)
                .body(new ApiError("not_found", "路径不存在: " + e.getResourcePath()));
    }

    /** 其它未预期异常 → 500 internal_error（V1 先隐藏细节，避免泄露内部信息） */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception e) {
        return ResponseEntity.status(500)
                .body(new ApiError("internal_error", "网关内部错误"));
    }
}
