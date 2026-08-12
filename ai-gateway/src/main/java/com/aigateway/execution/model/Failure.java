package com.aigateway.execution.model;

/**
 * 失败样本：一次失败的完整描述，在连接器 → 执行器 → 降级链之间传递。
 *
 * @param type          失败类型（决策的唯一依据）
 * @param reason        人可读描述（日志/指标）
 * @param upstreamStatus 上游 HTTP 状态码；无则 -1
 * @param retryable     是否允许触发重试/降级（由分类器 + 策略共同决定）
 * @param retryAfterMs  上游 Retry-After（毫秒）；无则 -1
 */
public record Failure(
        FailureType type,
        String reason,
        int upstreamStatus,
        boolean retryable,
        long retryAfterMs
) {
    /** 是否超时类失败（超时一律不可同实例重试，但可换候选） */
    public boolean isTimeout() {
        return switch (type) {
            case TIMEOUT_CONNECT, TIMEOUT_REQUEST, TIMEOUT_FIRST_BYTE,
                 TIMEOUT_IDLE, TIMEOUT_TOTAL -> true;
            default -> false;
        };
    }
}
