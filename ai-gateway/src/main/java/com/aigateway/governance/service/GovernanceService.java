package com.aigateway.governance.service;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.api.dto.ChatCompletion;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.core.service.ChatGatewayService;
import com.aigateway.core.service.ChatGatewayService.ChatResult;
import com.aigateway.governance.budget.BudgetManager;
import com.aigateway.governance.budget.TokenUsageStore;
import com.aigateway.governance.config.GovernanceProperties;
import com.aigateway.governance.ledger.UsageLedger;
import com.aigateway.governance.meter.TokenEstimator;
import com.aigateway.governance.meter.TokenMeter;
import com.aigateway.governance.model.ApiToken;
import com.aigateway.governance.model.BudgetScope;
import com.aigateway.governance.model.MeteredUsage;
import com.aigateway.governance.model.UsageRecord;
import com.aigateway.governance.ratelimit.RateLimitKey;
import com.aigateway.governance.ratelimit.RateLimitRule;
import com.aigateway.governance.ratelimit.RateLimiter;
import com.aigateway.governance.token.TokenManager;
import com.aigateway.observability.GatewayMetrics;
import com.aigateway.state.registry.ModelRegistry;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 治理编排服务：在 ChatGatewayService 外面包一层「预检 → 执行 → 计量 → 结算 → 留痕」
 * （脚手架完整实现，对照《版本4-详细实施计划》14.2 / 14.5）。
 *
 * 时序（对照学习版 4.4 / 设计文档 4.1 请求生命周期）：
 * 1. precheck：模型范围（H1）+ 令牌额度预检（12.2）——REJECT 模式不足直接 429；
 * 2. 执行：委托 ChatGatewayService（V3 合并后 = 决策 + 降级链执行）；
 * 3. 计量：非流式 meterNonStream（H4）；流式由 StreamProxy 的 MeteringCallback 增量累计；
 * 4. 结算：只有成功响应才扣减——令牌额度 + 实际使用的渠道/模型预算（H3）；
 * 5. 留痕：UsageLedger.record 入队（H6），失败请求也记（result=failure，成本 0）。
 */
@Service
public class GovernanceService {

    private final ChatGatewayService gateway;
    private final TokenManager tokenManager;
    private final TokenUsageStore usageStore;
    private final BudgetManager budgetManager;
    private final TokenMeter meter;
    private final UsageLedger ledger;
    private final RateLimiter rateLimiter;
    private final GovernanceProperties props;
    private final ModelRegistry registry;
    private final GatewayMetrics metrics;

    public GovernanceService(ChatGatewayService gateway, TokenManager tokenManager,
                             TokenUsageStore usageStore, BudgetManager budgetManager,
                             TokenMeter meter, UsageLedger ledger, RateLimiter rateLimiter,
                             GovernanceProperties props, ModelRegistry registry,
                             GatewayMetrics metrics) {
        this.gateway = gateway;
        this.tokenManager = tokenManager;
        this.usageStore = usageStore;
        this.budgetManager = budgetManager;
        this.meter = meter;
        this.ledger = ledger;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.registry = registry;
        this.metrics = metrics;
    }

    /** 非流式入口：治理化 complete */
    public ChatCompletion complete(ChatRequest request, String requestId,
                                   Map<String, String> metadata) {
        if (!props.isEnabled()) {
            // 旁路：enabled=false 时不做任何治理，V1~V3 行为原样
            return gateway.complete(request, requestId, metadata).completion();
        }
        ApiToken token = precheck(request, metadata);
        long start = System.nanoTime();
        try {
            long estIn = tokenRateAcquire(token, request);  // Token 速率占位（可选）
            ChatResult result = gateway.complete(request, requestId, metadata);
            MeteredUsage usage = meter.meterNonStream(
                    result.instance(), request, result.completion());
            settle(token, result, usage, true, elapsedMs(start), requestId);
            tokenRateAdjust(token, usage, estIn);           // 实际用量修正
            return result.completion();
        } catch (Exception e) {
            // 失败请求不修正 token 速率占位（窗口计数残留到过期；如需精确可在失败路径补
            // tokenRateAdjust 回吐——列入取舍说明，本版本从简）
            ledger.record(failureRecord(request, requestId, token, elapsedMs(start)));
            throw e;
        }
    }

    /** 流式入口：治理化 stream（计量靠 MeteringCallback 增量累计） */
    public void stream(ChatRequest request, String requestId, Map<String, String> metadata,
                       Consumer<ChatChunk> consumer) {
        if (!props.isEnabled()) {
            gateway.stream(request, requestId, metadata, consumer);   // 旁路
            return;
        }
        ApiToken token = precheck(request, metadata);
        long start = System.nanoTime();
        try {
            long estIn = tokenRateAcquire(token, request);  // Token 速率占位（可选）
            gateway.stream(request, requestId, metadata, consumer);
            // 流已正常结束：取流式计量结果——携带**实际执行实例**
            // （onFinish 的 MeteringContext 里是真实候选，不是首个候选）
            ModelInstance fallback = registry.findByAlias(request.model()).get(0);
            TokenMeter.MeteredResult mr = meter.takeResult(requestId, fallback, request);
            settle(token, new ChatResult(null, mr.instance()), mr.usage(),
                    true, elapsedMs(start), requestId);
            tokenRateAdjust(token, mr.usage(), estIn);      // 实际用量修正
        } catch (Exception e) {
            // 失败请求不修正 token 速率占位（窗口计数残留到过期；如需精确可在失败路径补
            // tokenRateAdjust 回吐——列入取舍说明，本版本从简）
            ledger.record(failureRecord(request, requestId, token, elapsedMs(start)));
            throw e;
        }
    }

    /** 预检：模型范围 + 令牌额度（REJECT 模式）；DEGRADE 模式额度不足只写信号不拦 */
    private ApiToken precheck(ChatRequest request, Map<String, String> metadata) {
        String tokenId = metadata.get("x-gateway-token-id");
        ApiToken token = tokenId == null ? null
                : tokenManager.findById(tokenId).orElse(null);
        if (token == null) {
            throw new GatewayException(401, "invalid_token", "请求未携带有效令牌");
        }
        if (!tokenManager.modelAllowed(token, request.model())) {
            throw new GatewayException(403, "token_model_forbidden",
                    "令牌无权调用模型: " + request.model());
        }
        // 额度预检（REJECT 模式硬拦；DEGRADE 模式放行，交给 BudgetFilter 自动降级）
        if ("REJECT".equals(props.getBudget().getExceedAction())
                && !usageStore.canPay(token, estimateAmount(token, request))) {
            throw new GatewayException(429, "budget_exceeded", "令牌额度不足");
        }
        return token;
    }

    /** 结算：成功才扣钱（预检通过但失败 → 不扣减，学习版 Q2）。
     *  量纲约定：令牌额度按令牌 quotaType 换算；
     *  预算按预算自身 limitType 取用（TOKEN 预算扣 totalTokens、COST 预算扣 cost）——
     *  调用方对预算无脑传双值，由 BudgetManager 内部选择，杜绝量纲错乱。 */
    private void settle(ApiToken token, ChatResult result, MeteredUsage usage,
                        boolean success, long latencyMs, String requestId) {
        // 令牌额度：COST → 美元；TOKEN → 总 token 数
        double tokenAmount = "TOKEN".equals(token.quotaType())
                ? usage.totalTokens() : usage.cost();
        usageStore.consume(token, tokenAmount);                // 令牌额度
        if (result.instance() != null) {
            // 预算：双值传入，预算内部按 limitType 选量纲
            budgetManager.consume(BudgetScope.CHANNEL, result.instance().channelId(),
                    usage.totalTokens(), usage.cost());
            budgetManager.consume(BudgetScope.MODEL, result.instance().model(),
                    usage.totalTokens(), usage.cost());
            budgetManager.consume(BudgetScope.GLOBAL, "",
                    usage.totalTokens(), usage.cost());
        }
        ledger.record(new UsageRecord(requestId, token.id(),
                requestAliasOf(result), instanceChannelOf(result), instanceModelOf(result),
                usage.tokenIn(), usage.tokenOut(), usage.cost(), latencyMs,
                success ? "success" : "failure", System.currentTimeMillis()));
        metrics.usage(usage, success);
    }

    /** 失败留痕：instance 未知，渠道/模型记空（账本按 tokenId 汇总不受影响） */
    private UsageRecord failureRecord(ChatRequest request, String requestId,
                                      ApiToken token, long latencyMs) {
        return new UsageRecord(requestId, token.id(), request.model(), "", "",
                0, 0, 0, latencyMs, "failure", System.currentTimeMillis());
    }

    /** 辅助取值（result.instance() 为 null 时返回占位） */
    private static String requestAliasOf(ChatResult r) {
        return r.instance() != null ? r.instance().alias() : "unknown";
    }
    private static String instanceChannelOf(ChatResult r) {
        return r.instance() != null ? r.instance().channelId() : "";
    }
    private static String instanceModelOf(ChatResult r) {
        return r.instance() != null ? r.instance().model() : "";
    }

    /** 预检额度估算：与 BudgetFilter 同口径（决策 defaultOutputTokens）；
     *  COST 令牌 → 预估成本（用首个候选单价近似）；TOKEN 令牌 → 预估总 token */
    private double estimateAmount(ApiToken token, ChatRequest request) {
        double charsPerToken = props.getEstimation().getCharsPerToken();
        long input = TokenEstimator.estimateInputTokens(request, charsPerToken,
                props.getEstimation().getCjkTokenPerChar());
        int output = request.maxTokens() != null ? request.maxTokens()
                : props.getDefaultOutputTokens();
        ModelInstance probe = registry.findByAlias(request.model()).get(0);
        return "TOKEN".equals(token.quotaType())
                ? input + output
                : input / 1000.0 * probe.priceIn() + output / 1000.0 * probe.priceOut();
    }

    /** Token 速率限流（可选开关）：请求开始用预估输入 token 占位；
     *  返回预估输入 token 数（未启用返回 0，供结束修正用） */
    private long tokenRateAcquire(ApiToken token, ChatRequest request) {
        if (!props.getRateLimit().getTokenRate().isEnabled()) return 0;
        long est = TokenEstimator.estimateInputTokens(request,
                props.getEstimation().getCharsPerToken(),
                props.getEstimation().getCjkTokenPerChar());
        RateLimitRule rule = new RateLimitRule(RateLimitKey.token(token.id()),
                TimeUnit.SECONDS.toMillis(1),
                props.getRateLimit().getTokenRate().getPerSecondTokens(), true);
        if (!rateLimiter.tryAcquire(rule, est)) {
            metrics.rateLimited("token_rate");
            throw new GatewayException(429, "rate_limit_exceeded", "Token 速率超限");
        }
        return est;
    }

    /** Token 速率修正：把占位量换成实际量（实际输入 - 预估输入，可负 = 回吐） */
    private void tokenRateAdjust(ApiToken token, MeteredUsage usage, long estIn) {
        if (!props.getRateLimit().getTokenRate().isEnabled()) return;
        long delta = usage.tokenIn() - estIn;
        if (delta != 0) {
            rateLimiter.adjust(RateLimitKey.token(token.id()),
                    TimeUnit.SECONDS.toMillis(1), delta);
        }
    }

    /** 耗时换算：nanoTime 差值 → 毫秒（与 V1~V3 同款，不受系统改时间影响） */
    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
