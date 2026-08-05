package com.aigateway.infra.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 为每个请求生成 requestId，写入请求属性与响应头（X-Request-Id）。
 */
@Component
public class RequestIdFilter implements Filter {

    public static final String ATTR = "requestId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ATTR, requestId);
        if (response instanceof HttpServletResponse httpResponse) {
            httpResponse.setHeader("X-Request-Id", requestId);
        }
        chain.doFilter(request, response);
    }
}
