package com.aigateway.core.domain.model;

/**
 * 渠道：一个上游端点（供应商账号/自建服务）。
 */
public record Channel(
        String id,
        String provider,
        String baseUrl,
        String credentialsRef,
        int weight
) {
    public boolean needAuth() {
        return credentialsRef != null && !credentialsRef.isBlank();
    }
}
