package com.aigateway.decision.filter;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.governance.budget.TokenUsageStore;
import com.aigateway.governance.config.GovernanceProperties;
import com.aigateway.governance.model.ApiToken;
import com.aigateway.governance.persistence.TokenDao;
import com.aigateway.governance.persistence.UsageDao;
import com.aigateway.governance.token.TokenManager;
import com.aigateway.observability.GatewayMetrics;
import com.aigateway.plugin.context.PluginContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H5 单测骨架（对照《版本4-详细实施计划》12.5）。
 * 手敲完成 BudgetFilter 后删除 @Disabled 即可运行。
 */
@Disabled("TODO H5：手敲 BudgetFilter 完成后启用")
class BudgetFilterTest {

    private TokenManager tokenManager;
    private TokenUsageStore store;
    private BudgetFilter filter;

    @BeforeEach
    void setUp() {
        tokenManager = mock(TokenManager.class);
        store = new TokenUsageStore(mock(TokenDao.class), mock(UsageDao.class));
        filter = new BudgetFilter(store, tokenManager, testProps(), mock(GatewayMetrics.class));
    }

    @Test
    void channelBudgetExceeded_removesChannelCandidates() {
        PluginContext ctx = ctxWithCandidates(instance("mock-a:qwen-large"),
                instance("mock-b:qwen-small"));
        ctx.signals().set("governance.budget_exceeded_channels", Set.of("mock-a"));

        List<ModelInstance> kept = filter.apply(request("你好"), ctx, ctx.candidates());
        assertThat(kept).extracting(ModelInstance::channelId).containsExactly("mock-b");
    }

    @Test
    void insufficientTokenQuota_keepsOnlyAffordable() {
        // 令牌剩余 $0.0005：qwen-large 预估 ~$0.001（付不起）、qwen-small 预估 ~$0.00026（付得起）
        ApiToken token = new ApiToken("agw_00000001", "t", "hash", 0.0005, "COST", "", "", -1, true, 0);
        PluginContext ctx = ctxWithCandidates(instance("mock-a:qwen-large", 0.002, 0.004),
                instance("mock-b:qwen-small", 0.0005, 0.001));
        ctx.signals().set("governance.token_id", token.id());
        when(tokenManager.findById(token.id())).thenReturn(Optional.of(token));

        List<ModelInstance> kept = filter.apply(request("你好"), ctx, ctx.candidates());
        assertThat(kept).extracting(ModelInstance::instanceId).containsExactly("mock-b:qwen-small");
        // 信号已写入：Scorer 动态调权自动生效
        assertThat(ctx.signals().get("budget_remaining_ratio")).hasValueSatisfying(v ->
                assertThat(((Number) v).doubleValue()).isCloseTo(1.0, within(0.001)));
    }

    @Test
    void allDropped_returnsBudgetExceeded() {
        // 额度 $0.0001：两个候选都付不起 → 全淘汰
        ApiToken token = new ApiToken("agw_00000001", "t", "hash", 0.0001, "COST", "", "", -1, true, 0);
        PluginContext ctx = ctxWithCandidates(instance("mock-a:qwen-large", 0.001, 0.002));
        ctx.signals().set("governance.token_id", token.id());
        when(tokenManager.findById(token.id())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> filter.apply(request("你好"), ctx, ctx.candidates()))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> {
                    assertThat(((GatewayException) e).getStatus()).isEqualTo(429);
                    assertThat(((GatewayException) e).getType()).isEqualTo("budget_exceeded");
                });
    }

    // —— 测试辅助 ——
    private static GovernanceProperties testProps() {
        GovernanceProperties props = new GovernanceProperties();
        props.getEstimation().setCharsPerToken(4.0);
        props.getEstimation().setCjkTokenPerChar(1.0);
        return props;
    }

    private static ModelInstance instance(String instanceId) {
        return instance(instanceId, 0.001, 0.002);
    }

    private static ModelInstance instance(String instanceId, double priceIn, double priceOut) {
        String[] parts = instanceId.split(":");
        return new ModelInstance(instanceId, "qwen", parts[0], parts[1],
                1, null, priceIn, priceOut, 0.5, 1000);
    }

    private static ChatRequest request(String content) {
        return new ChatRequest("qwen",
                List.of(new ChatRequest.Message("user", content)), false, null, null);
    }

    private static PluginContext ctxWithCandidates(ModelInstance... candidates) {
        return new PluginContext(request("你好"), "rid-1", "qwen", java.util.Map.of(),
                java.util.Arrays.asList(candidates));
    }
}
