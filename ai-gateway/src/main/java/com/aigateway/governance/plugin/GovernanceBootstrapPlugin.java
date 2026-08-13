package com.aigateway.governance.plugin;

import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.governance.budget.BudgetManager;
import com.aigateway.governance.budget.TokenUsageStore;
import com.aigateway.governance.config.GovernanceProperties;
import com.aigateway.governance.model.BudgetScope;
import com.aigateway.governance.token.TokenManager;
import com.aigateway.plugin.context.PluginContext;
import com.aigateway.plugin.context.Signals;
import com.aigateway.plugin.spi.GatewayPlugin;
import com.aigateway.plugin.spi.PluginScope;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 治理插件（BEFORE_DECISION，order=100 最先执行）：
 * 把治理状态写进信号，供 BudgetFilter（H5）与 Scorer 消费。
 * 不淘汰任何候选——淘汰动作统一收敛在 BudgetFilter，插件只负责“情报”。
 *
 * 信号链路（对照《版本4-详细实施计划》12.1）：
 * - governance.token_id / token_remaining_ratio：BudgetFilter 查令牌额度、Scorer 动态调权；
 * - governance.budget_exceeded_channels / models：渠道/模型预算触顶集合（候选逐个查预算）。
 *
 * ⚠️ governance.enabled=false 时旁路（V1~V3 行为原样）：不写信号、不查预算——
 * BudgetManager.remainingRatio 是 H3 手敲点，未手敲/禁用时不能拖垮决策链。
 */
@Component
public class GovernanceBootstrapPlugin implements GatewayPlugin {

    private final GovernanceProperties props;
    private final TokenManager tokenManager;
    private final TokenUsageStore usageStore;
    private final BudgetManager budgetManager;

    public GovernanceBootstrapPlugin(GovernanceProperties props, TokenManager tokenManager,
                                     TokenUsageStore usageStore, BudgetManager budgetManager) {
        this.props = props;
        this.tokenManager = tokenManager;
        this.usageStore = usageStore;
        this.budgetManager = budgetManager;
    }

    @Override
    public String name() { return "governance-bootstrap"; }

    @Override
    public PluginScope scope() { return PluginScope.GLOBAL; }

    @Override
    public int order() { return 100; }     // 数字大先执行：必须赶在过滤器读信号之前

    @Override
    public void beforeDecision(PluginContext ctx) {
        if (!props.isEnabled()) return;    // 治理禁用：完全旁路
        Signals s = ctx.signals();
        // 1. 令牌情报：GovernanceFilter 已把 tokenId 放进 metadata
        String tokenId = ctx.metadata().get("x-gateway-token-id");
        if (tokenId != null) {
            tokenManager.findById(tokenId).ifPresent(token -> {
                s.set("governance.token_id", tokenId);
                s.set("governance.token_remaining_ratio", usageStore.remainingRatio(token));
            });
        }
        // 2. 渠道/模型预算触顶集合：候选逐个查预算（预算未配置 = 不限）
        Set<String> channels = new HashSet<>();
        Set<String> models = new HashSet<>();
        for (ModelInstance inst : ctx.candidates()) {
            if (budgetManager.remainingRatio(BudgetScope.CHANNEL, inst.channelId()) <= 0) {
                channels.add(inst.channelId());
            }
            if (budgetManager.remainingRatio(BudgetScope.MODEL, inst.model()) <= 0) {
                models.add(inst.model());
            }
        }
        if (!channels.isEmpty()) s.set("governance.budget_exceeded_channels", channels);
        if (!models.isEmpty()) s.set("governance.budget_exceeded_models", models);
    }
}
