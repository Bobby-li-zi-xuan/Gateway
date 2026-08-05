package com.aigateway.infra.web;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 为每个请求生成 requestId，并注入响应链路（X-Request-Id 头）。
 */
@Component
public class RequestIdFilter implements WebFilter {

    public static final String ATTR = "requestId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = UUID.randomUUID().toString();
        exchange.getAttributes().put(ATTR, requestId);
        ServerWebExchange mutated = exchange.mutate()
                .request(builder -> builder.header("X-Request-Id", requestId))
                .build();
        return chain.filter(mutated);
    }
}
