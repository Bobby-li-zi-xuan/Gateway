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
 *
 * 学习要点：
 * - 过滤器在 Controller 之前执行，Controller / Service 通过
 *   {@code request.getAttribute(ATTR)} 拿到同一个 ID，实现全链路贯穿；
 * - 日志统一打印 [requestId=xxx]，可以把一次请求的所有日志串起来排查；
 * - 响应头 X-Request-Id 方便客户端或下游系统关联日志。
 */
@Component
public class RequestIdFilter implements Filter {

    /** 请求属性名：Controller/Service 用它取 requestId */
    public static final String ATTR = "requestId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // 1. 生成本次请求的唯一 ID
        String requestId = UUID.randomUUID().toString();
        // 2. 写入请求属性，后续任何一层都能取到
        request.setAttribute(ATTR, requestId);
        // 3. 写入响应头，客户端可直接看到
        if (response instanceof HttpServletResponse httpResponse) {
            httpResponse.setHeader("X-Request-Id", requestId);
        }
        // 4. 继续走过滤器链，最后进入 Controller
        chain.doFilter(request, response);
    }
}
