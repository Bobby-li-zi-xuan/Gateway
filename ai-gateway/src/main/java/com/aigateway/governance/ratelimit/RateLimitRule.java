package com.aigateway.governance.ratelimit;

/** 一条限流规则：窗口内最多 limit 个「单位」（1 请求 或 1 token） */
public record RateLimitRule(
        RateLimitKey key,
        long windowMs,       // 窗口长度：QPS=1000、RPM=60000
        long limit,
        boolean tokenMode    // true = 按 token 计数（预扣预估输入，结束修正）
) {}
