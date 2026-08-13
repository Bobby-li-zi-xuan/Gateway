package com.aigateway.governance.model;

/** 单次用量记录（异步批量落库；字段与 usage_record 表一一对应） */
public record UsageRecord(
        String requestId,
        String tokenId,
        String alias,
        String channelId,
        String model,
        long tokenIn,
        long tokenOut,
        double cost,
        long latencyMs,
        String result,        // success / failure（失败不扣预算但留痕）
        long createdAt
) {}
