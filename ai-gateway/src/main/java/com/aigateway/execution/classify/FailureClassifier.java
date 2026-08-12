package com.aigateway.execution.classify;

import com.aigateway.execution.model.Failure;
import com.aigateway.execution.model.FailureType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Set;

/**
 * 失败分类器（脚手架）：把"异常 + HTTP 状态码"映射成结构化 Failure。
 *
 * 分类规则（对照学习版 4.1 重试决策表）：
 * - HTTP 429 → HTTP_429（retryable=true，读 retryableStatuses）；Retry-After 由连接器解析传入；
 * - HTTP 5xx → HTTP_5XX（retryable=true，读 retryableStatuses）；
 * - 其它非 2xx → HTTP_OTHER（是否可重试由 retryableStatuses 决定，4xx 业务错误默认不可重试）；
 * - 网络异常 → CONNECTION（唯一允许同实例重试的类型，retryable=true）；
 * - JDK HttpClient 超时 → TIMEOUT_CONNECT（连接阶段）/ TIMEOUT_REQUEST（完整响应阶段）。
 *
 * 判 retryable 时读 RetryPolicy.retryableStatuses（429/5xx 默认可重试，其余状态码可配置）。
 */
@Component
public class FailureClassifier {

    /** HTTP 状态码 → Failure（连接器非 2xx 分支调用；retryAfterMs 由连接器从响应头解析） */
    public Failure classifyStatus(int status, String reason, long retryAfterMs,
                                  Set<Integer> retryableStatuses) {
        boolean retryable = retryableStatuses.contains(status);
        FailureType type = switch (status) {
            case 429 -> FailureType.HTTP_429;
            case 500, 502, 503, 504 -> FailureType.HTTP_5XX;
            default -> FailureType.HTTP_OTHER;
        };
        return new Failure(type, reason, status, retryable, retryAfterMs);
    }

    /** 网络异常 → Failure（连接器 catch 分支调用） */
    public Failure classifyException(Throwable e) {
        if (e instanceof HttpConnectTimeoutException) {
            return new Failure(FailureType.TIMEOUT_CONNECT, "连接超时: " + e.getMessage(),
                    -1, true, -1);
        }
        if (e instanceof HttpTimeoutException) {
            return new Failure(FailureType.TIMEOUT_REQUEST, "请求超时: " + e.getMessage(),
                    -1, true, -1);
        }
        if (e instanceof ConnectException) {
            return new Failure(FailureType.CONNECTION, "连接失败: " + e.getMessage(),
                    -1, true, -1);
        }
        if (e instanceof IOException) {
            return new Failure(FailureType.CONNECTION, "IO 异常: " + e.getMessage(),
                    -1, true, -1);
        }
        return new Failure(FailureType.UNKNOWN, "未知异常: " + e.getMessage(), -1, false, -1);
    }
}
