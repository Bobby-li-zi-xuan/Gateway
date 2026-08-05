package com.aigateway.infra.http;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 上游 HTTP 客户端（JDK 自带），连接超时统一 500ms；
 * 请求级超时由调用方（Connector / HealthChecker）自行设置。
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
