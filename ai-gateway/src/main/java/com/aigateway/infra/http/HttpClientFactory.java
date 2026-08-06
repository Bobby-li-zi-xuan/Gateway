package com.aigateway.infra.http;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 上游 HTTP 客户端（JDK 自带），统一管理连接参数。
 *
 * 学习要点：
 * - 连接超时 500ms 只覆盖“建立 TCP 连接”这一阶段；
 *   请求级超时（发请求到收完整响应）由调用方（Connector / HealthChecker）单独设置；
 * - 全局只创建一个 HttpClient 单例（Bean），底层连接池复用，避免每次请求新建。
 */
@Configuration
public class HttpClientFactory {

    @Bean
    public HttpClient gatewayHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .build();
    }
}
