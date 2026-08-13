package com.aigateway.governance.filter;

import com.aigateway.governance.config.GovernanceProperties;
import com.aigateway.governance.model.ApiToken;
import com.aigateway.governance.model.TokenStatus;
import com.aigateway.governance.ratelimit.RateLimitKey;
import com.aigateway.governance.ratelimit.RateLimitRule;
import com.aigateway.governance.ratelimit.RateLimiter;
import com.aigateway.governance.token.TokenManager;
import com.aigateway.observability.GatewayMetrics;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 治理过滤器（注册为 @Order(2) 的 Filter，与无 @Order 的 RequestIdFilter 相比先执行；顺序无功能影响）：
 * 1. 解析 Authorization: Bearer <token> → TokenManager.resolve（H1）→ 状态分类错误映射；
 * 2. 令牌级 QPS / RPM 限流（H2）——不读请求体，因此 Filter 层只做这两件事；
 * 3. 校验通过把 tokenId 写入 request attribute，Controller 透传进 metadata。
 *
 * 设计取舍（面试可讲）：
 * - 模型范围、Token 速率、预算预检需要请求体（model / messages），放服务层做；
 * - Filter 层保持「零 body 读取」，不包装请求流，避免缓存 body 的内存开销；
 * - governance.enabled=false 时整体旁路，V1~V3 行为原样。
 */
@Component
@Order(2)
public class GovernanceFilter implements Filter {

    public static final String ATTR_TOKEN_ID = "governance.tokenId";

    private final GovernanceProperties props;
    private final TokenManager tokenManager;
    private final RateLimiter rateLimiter;
    private final GatewayMetrics metrics;

    public GovernanceFilter(GovernanceProperties props, TokenManager tokenManager,
                            RateLimiter rateLimiter, GatewayMetrics metrics) {
        this.props = props;
        this.tokenManager = tokenManager;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        if (!props.isEnabled() || !httpReq.getRequestURI().equals("/v1/chat/completions")) {
            chain.doFilter(req, res);
            return;
        }

        // 1. 鉴权：Bearer token → 令牌对象（存在/启用/过期/IP 已核）→ 错误映射
        String plain = bearer(httpReq.getHeader("Authorization"));
        ApiToken token = tokenManager.resolve(plain, clientIp(httpReq))
                .orElse(null);
        if (token == null) {
            TokenStatus status = tokenManager.validate(plain, clientIp(httpReq));
            writeError((HttpServletResponse) res, status);
            return;
        }
        // 2. 令牌级 QPS / RPM 限流（短窗管突发、长窗管总量）
        RateLimitRule qps = new RateLimitRule(RateLimitKey.token(token.id()),
                TimeUnit.SECONDS.toMillis(1), props.getRateLimit().getDefaultQps(), false);
        RateLimitRule rpm = new RateLimitRule(RateLimitKey.token(token.id()),
                TimeUnit.MINUTES.toMillis(1), props.getRateLimit().getDefaultRpm(), false);
        if (!rateLimiter.tryAcquire(qps, 1) || !rateLimiter.tryAcquire(rpm, 1)) {
            metrics.rateLimited("token");
            writeError((HttpServletResponse) res, TokenStatus.RATE_LIMITED);
            return;
        }
        // 3. 写 attribute → Controller metadata 透传
        httpReq.setAttribute(ATTR_TOKEN_ID, token.id());
        chain.doFilter(req, res);
    }

    /** Bearer 解析：无头/格式错都返回 null（后续走 MISSING/UNKNOWN 分类） */
    private static String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        String v = authorization.substring(7).trim();
        return v.isEmpty() ? null : v;
    }

    /** 客户端 IP（X-Forwarded-For 首段，演示环境直连取 remoteAddr） */
    private static String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    /** 校验失败 → 统一错误体（错误码表见 17.2；RATE_LIMITED 带 Retry-After） */
    private static void writeError(HttpServletResponse res, TokenStatus status) throws IOException {
        int httpStatus = switch (status) {
            case MISSING, UNKNOWN -> 401;
            case DISABLED, EXPIRED, IP_DENIED, MODEL_FORBIDDEN -> 403;
            case QUOTA_EXHAUSTED, RATE_LIMITED -> 429;
            default -> 500;
        };
        String type = switch (status) {
            case MISSING, UNKNOWN -> "invalid_token";
            case DISABLED -> "token_disabled";
            case EXPIRED -> "token_expired";
            case IP_DENIED -> "token_ip_denied";
            case MODEL_FORBIDDEN -> "token_model_forbidden";
            case QUOTA_EXHAUSTED -> "budget_exceeded";
            case RATE_LIMITED -> "rate_limit_exceeded";
            default -> "internal_error";
        };
        if (status == TokenStatus.RATE_LIMITED) {
            res.setHeader("Retry-After", "1");     // 演示友好：1 秒后重试
        }
        res.setStatus(httpStatus);
        res.setContentType("application/json");
        res.getWriter().write("{\"type\":\"" + type + "\",\"message\":\""
                + describe(status) + "\"}");
    }

    private static String describe(TokenStatus s) {
        return switch (s) {
            case MISSING -> "缺少 Authorization: Bearer 令牌";
            case UNKNOWN -> "令牌不存在或已吊销";
            case DISABLED -> "令牌已被禁用";
            case EXPIRED -> "令牌已过期";
            case IP_DENIED -> "来源 IP 不在白名单";
            case MODEL_FORBIDDEN -> "该令牌无权调用此模型";
            case QUOTA_EXHAUSTED -> "令牌额度已用尽";
            case RATE_LIMITED -> "请求频率超限";
            default -> "未知错误";
        };
    }
}
