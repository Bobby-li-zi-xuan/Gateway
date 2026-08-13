package com.aigateway.governance.service;

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
import com.aigateway.governance.meter.TokenMeter;
import com.aigateway.governance.model.ApiToken;
import com.aigateway.governance.model.BudgetScope;
import com.aigateway.governance.model.MeteredUsage;
import com.aigateway.governance.model.UsageRecord;
import com.aigateway.governance.ratelimit.RateLimiter;
import com.aigateway.governance.token.TokenManager;
import com.aigateway.observability.GatewayMetrics;
import com.aigateway.state.registry.ModelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 脚手架测试：GovernanceService 接线（成功结算 / 失败不扣钱 / REJECT 预检拦截，对照 20.1）。
 * 手敲依赖（H1~H6 组件）全部 mock，只验证接线编排本身。
 */
class GovernanceServiceTest {

    private ChatGatewayService gateway;
    private TokenManager tokenManager;
    private TokenUsageStore usageStore;
    private BudgetManager budgetManager;
    private TokenMeter meter;
    private UsageLedger ledger;
    private GovernanceService service;

    private static final ApiToken TOKEN =
            new ApiToken("agw_00000001", "t", "hash", 1.0, "COST", "", "", -1, true, 0);

    @BeforeEach
    void setUp() {
        gateway = mock(ChatGatewayService.class);
        tokenManager = mock(TokenManager.class);
        usageStore = mock(TokenUsageStore.class);
        budgetManager = mock(BudgetManager.class);
        meter = mock(TokenMeter.class);
        ledger = mock(UsageLedger.class);
        RateLimiter rateLimiter = mock(RateLimiter.class);
        ModelRegistry registry = mock(ModelRegistry.class);
        GatewayMetrics metrics = mock(GatewayMetrics.class);
        when(registry.findByAlias("qwen")).thenReturn(List.of(instance()));   // 预检估算用首个候选单价

        GovernanceProperties props = new GovernanceProperties();
        props.setEnabled(true);
        props.getBudget().setExceedAction("REJECT");

        service = new GovernanceService(gateway, tokenManager, usageStore, budgetManager,
                meter, ledger, rateLimiter, props, registry, metrics);
    }

    @Test
    void success_settlesTokenAndBudgets() {
        when(tokenManager.findById(TOKEN.id())).thenReturn(Optional.of(TOKEN));
        when(tokenManager.modelAllowed(TOKEN, "qwen")).thenReturn(true);
        when(usageStore.canPay(eq(TOKEN), anyDouble())).thenReturn(true);

        ModelInstance inst = instance();
        ChatCompletion completion = new ChatCompletion("id", "chat.completion", 0, "qwen",
                List.of(new ChatCompletion.Choice(0,
                        new ChatCompletion.Choice.Message("assistant", "hi"), "stop")), null);
        when(gateway.complete(any(), any(), any())).thenReturn(new ChatResult(completion, inst));
        MeteredUsage usage = new MeteredUsage(10, 20, 0.001, false);
        when(meter.meterNonStream(eq(inst), any(), any())).thenReturn(usage);

        ChatCompletion result = service.complete(request(), "rid-1",
                Map.of("x-gateway-token-id", TOKEN.id()));

        assertThat(result).isSameAs(completion);
        // COST 令牌：按美元扣减
        verify(usageStore).consume(eq(TOKEN), eq(0.001));
        // 预算：双值传入（totalTokens / cost），BudgetManager 内部按 limitType 取用
        verify(budgetManager).consume(eq(BudgetScope.CHANNEL), eq("mock-a"), eq(30L), eq(0.001));
        verify(budgetManager).consume(eq(BudgetScope.MODEL), eq("qwen"), eq(30L), eq(0.001));
        verify(budgetManager).consume(eq(BudgetScope.GLOBAL), eq(""), eq(30L), eq(0.001));
        // 留痕：成功记录
        verify(ledger).record(any(UsageRecord.class));
    }

    @Test
    void failure_doesNotDeduct_butRecordsFailure() {
        when(tokenManager.findById(TOKEN.id())).thenReturn(Optional.of(TOKEN));
        when(tokenManager.modelAllowed(TOKEN, "qwen")).thenReturn(true);
        when(usageStore.canPay(eq(TOKEN), anyDouble())).thenReturn(true);
        when(gateway.complete(any(), any(), any()))
                .thenThrow(new RuntimeException("upstream down"));

        assertThatThrownBy(() -> service.complete(request(), "rid-2",
                Map.of("x-gateway-token-id", TOKEN.id())))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("upstream down");

        // 失败不扣钱（学习版 Q2）
        verify(usageStore, never()).consume(eq(TOKEN), anyDouble());
        verify(budgetManager, never()).consume(any(), any(), anyLong(), anyDouble());
        // 但留痕：result=failure
        verify(ledger).record(org.mockito.ArgumentMatchers.argThat(
                (UsageRecord r) -> "failure".equals(r.result()) && r.cost() == 0));
    }

    @Test
    void rejectMode_blocksAtPrecheck() {
        when(tokenManager.findById(TOKEN.id())).thenReturn(Optional.of(TOKEN));
        when(tokenManager.modelAllowed(TOKEN, "qwen")).thenReturn(true);
        when(usageStore.canPay(eq(TOKEN), anyDouble())).thenReturn(false);   // 额度不足

        assertThatThrownBy(() -> service.complete(request(), "rid-3",
                Map.of("x-gateway-token-id", TOKEN.id())))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> {
                    assertThat(((GatewayException) e).getStatus()).isEqualTo(429);
                    assertThat(((GatewayException) e).getType()).isEqualTo("budget_exceeded");
                });
        // 预检拦截：不触达执行层
        verify(gateway, never()).complete(any(), any(), any());
    }

    // —— 测试辅助 ——
    private static ChatRequest request() {
        return new ChatRequest("qwen",
                List.of(new ChatRequest.Message("user", "hi")), false, null, null);
    }

    private static ModelInstance instance() {
        return new ModelInstance("mock-a:qwen", "qwen", "mock-a", "qwen",
                1, null, 0.001, 0.002, 0.5, 1000);
    }
}
