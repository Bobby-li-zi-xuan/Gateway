package com.aigateway.decision.filter;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.governance.budget.TokenUsageStore;
import com.aigateway.governance.config.GovernanceProperties;
import com.aigateway.governance.meter.TokenEstimator;
import com.aigateway.governance.model.ApiToken;
import com.aigateway.governance.token.TokenManager;
import com.aigateway.observability.GatewayMetrics;
import com.aigateway.plugin.context.PluginContext;
import com.aigateway.plugin.context.Signals;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 第 4 道：预算过滤
 *
 * 淘汰规则（对照学习版 4.6 超额策略 / Envoy QuotaPolicy）：
 * 1. 渠道预算触顶（governance.budget_exceeded_channels）→ 淘汰该渠道候选；
 * 2. 模型预算触顶（governance.budget_exceeded_models）→ 淘汰该模型候选；
 * 3. 令牌额度付不起预估成本 → 淘汰（DEGRADE 模式的自动降级核心：贵的淘汰，便宜的自然上位）；
 * 4. 全部淘汰 → 429 budget_exceeded（比 FilterChain 的 503 no_available_model 语义更准确）；
 * 5. 写 budget_remaining_ratio 信号 → Scorer 动态调权自动生效（成本权重放大，零改动）。
 *
 * 预估成本口径与 Scorer 一致：输入 = 请求文本估算，输出 = maxTokens 或 defaultOutputTokens
 * - apply()：读信号集合 → 逐候选淘汰（渠道/模型触顶、令牌付不起）→
 *   全淘汰抛 GatewayException(429, "budget_exceeded", ...)（先 metrics.filterDrops +
 *   metrics.budgetRejected 埋点）→ 写 budget_remaining_ratio 信号 → 返回 kept。
 */
@Component
public class BudgetFilter implements CandidateFilter {

    private final TokenUsageStore usageStore;
    private final TokenManager tokenManager;
    private final GovernanceProperties props;
    private final GatewayMetrics metrics;

    public BudgetFilter(TokenUsageStore usageStore, TokenManager tokenManager,
                        GovernanceProperties props, GatewayMetrics metrics) {
        this.usageStore = usageStore;
        this.tokenManager = tokenManager;
        this.props = props;
        this.metrics = metrics;
    }

    public int order() { return 20; }

    public String name() { return "budget"; }

    public List<ModelInstance> apply(ChatRequest request, PluginContext ctx,
                                     List<ModelInstance> candidates) {
        // 见类注释的淘汰规则；Signals.getStringSet 读触顶集合、
        // tokenManager.findById 取令牌、usageStore.canPay 判额度
        Signals s = ctx.signals();
        Set<String> exceededChannels = s.getStringSet("governance.budget_exceeded_channels")
                                        .orElse(Set.of());
        Set<String> exceededModels = s.getStringSet("governance.budget_exceeded_models")
                                        .orElse(Set.of());
        String tokenId = s.getString("governance.token_id").orElse(null);
        ApiToken token = tokenId == null ? null : tokenManager.findById(tokenId).orElse(null);
        
        List<ModelInstance> kept = new ArrayList<>();
        for (ModelInstance inst : candidates) {
            if (exceededChannels.contains(inst.channelId())) continue;   // 渠道预算触顶
            if (exceededModels.contains(inst.model())) continue;         // 模型预算触顶
            if (token != null && !usageStore.canPay(token, estimateCost(inst, request))) {
                continue;                                                // 令牌额度付不起（自动降级）
            }
            kept.add(inst);
        }

        if (kept.isEmpty()) {
            // 全淘汰也要埋点：FilterChain 的 filterDrops 只在 apply 正常返回后计数，
            // 这里抛异常前补一次，避免“全淘汰场景无淘汰指标”（审查修正 #9）
            metrics.filterDrops(ctx.alias(), name(), candidates.size());
            metrics.budgetRejected("token");
            // 过滤链 fail-closed 之前先给出更准确的语义：预算花完了
            throw new GatewayException(429, "budget_exceeded",
                    "预算/额度不足，没有可用的低成本候选");
        }

        // 写剩余比率信号 → Scorer 成本权重放大（预算紧张时自动省着花）
        double ratio = token != null ? usageStore.remainingRatio(token) : 1.0;
        ctx.signals().set("budget_remaining_ratio", ratio);
        return kept;
    }

    /** 预估成本：与 Scorer.estimateCost 同口径（单价表 × 预估 token） */
    private double estimateCost(ModelInstance inst, ChatRequest request) {
        double charsPerToken = props.getEstimation().getCharsPerToken();
        double cjkTokenPerChar = props.getEstimation().getCjkTokenPerChar();
        long inputTokens = TokenEstimator.estimateInputTokens(request, charsPerToken, cjkTokenPerChar);
        int outputTokens = request.maxTokens() != null ? request.maxTokens()
                : props.getDefaultOutputTokens();   // 复用 decision.defaultOutputTokens
        return inputTokens / 1000.0 * inst.priceIn()
                + outputTokens / 1000.0 * inst.priceOut();
    }
}
