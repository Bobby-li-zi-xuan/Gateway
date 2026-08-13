package com.aigateway.governance.model;

/**
 * 令牌校验结果分类：校验失败的类型决定错误码与 HTTP 状态（《版本4-详细实施计划》17.2）。
 * RATE_LIMITED 为 V4 脚手架补充（14.1）：限流拒绝不属令牌校验，但错误映射统一走本枚举。
 */
public enum TokenStatus {
    VALID,                 // 通过：继续走限流与决策
    MISSING,               // 未携带 Authorization 头 → 401 invalid_token
    UNKNOWN,               // 令牌不存在 → 401 invalid_token
    DISABLED,              // 已吊销/停用 → 403 token_disabled
    EXPIRED,               // 已过期 → 403 token_expired
    IP_DENIED,             // IP 不在白名单 → 403 token_ip_denied
    MODEL_FORBIDDEN,       // 请求模型不在 modelScope → 403 token_model_forbidden
    QUOTA_EXHAUSTED,       // 额度预检不足 → 429 budget_exceeded
    RATE_LIMITED           // 限流超限 → 429 rate_limit_exceeded（带 Retry-After）
}
