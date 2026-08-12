package com.aigateway.infra.http;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上游 HTTP 客户端（JDK 自带），统一管理连接参数。
 *
 * 学习要点：
 * - 连接超时只覆盖"建立 TCP 连接"这一阶段；
 *   请求级超时（发请求到收完整响应）由调用方（Connector / HealthChecker）单独设置；
 * - V3 分层超时：连接超时按渠道独立配置（connectMs），
 *   {@link #clientFor} 按连接超时缓存客户端，避免每次请求新建、又能复用连接池；
 * - 全局默认客户端（500ms 连接超时）保留，供 HealthChecker 等组件注入。
 */
@Configuration
public class HttpClientFactory {

    /** 连接超时 → HttpClient（同一 connectMs 共享连接池，避免重复创建） */
    private final Map<Long, HttpClient> byConnectMs = new ConcurrentHashMap<>();

    @Bean
    public HttpClient gatewayHttpClient() {
        return clientFor(500);
    }

    /** 按连接超时取（或创建）HttpClient；V3 连接器用 policies.timeout().connectMs() 调用 */
    public HttpClient clientFor(long connectMs) {
        return byConnectMs.computeIfAbsent(connectMs, ms -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(ms))
                .build());
    }
}
