package com.aigateway.execution.fallback;

import com.aigateway.api.dto.ChatCompletion;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.core.service.ChatGatewayService.ChatResult;
import com.aigateway.decision.state.ModelStateStore;
import com.aigateway.decision.state.StateEventPipeline;
import com.aigateway.execution.circuit.CircuitBreakerRegistry;
import com.aigateway.execution.connector.OpenAIConnector;
import com.aigateway.execution.cooldown.CooldownManager;
import com.aigateway.execution.model.CircuitBreakerConfig;
import com.aigateway.execution.model.CooldownConfig;
import com.aigateway.execution.model.ExecutionPolicies;
import com.aigateway.execution.model.Failure;
import com.aigateway.execution.model.FailureType;
import com.aigateway.execution.model.RetryPolicy;
import com.aigateway.execution.model.TimeoutPolicy;
import com.aigateway.execution.model.UpstreamCallException;
import com.aigateway.execution.policy.ExecutionPolicyManager;
import com.aigateway.execution.retry.RetryExecutor;
import com.aigateway.observability.GatewayMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H5 单测（详细实施计划 18.1 H5 行）：降级链三件事——
 * 主选失败换候选、熔断/冷却跳过、总时长预算硬边界。
 *
 * 依赖说明：9 个依赖全部 mock（retryExecutor 转发给真实 Attempt 调用）；
 * scheduler 用真实调度器（TimeoutGuard 构造需要）。
 */
class FallbackChainExecutorTest {

    private static final ModelInstance CANDIDATE_A = new ModelInstance(
            "mock-a:qwen-large", "qwen", "mock-a", "qwen-large", 1, null,
            0.001, 0.002, 0.9, 1000L);
    private static final ModelInstance CANDIDATE_B = new ModelInstance(
            "mock-b:qwen-small", "qwen", "mock-b", "qwen-small", 1, null,
            0.001, 0.002, 0.9, 1000L);
    private static final ChatRequest REQUEST = new ChatRequest(
            "qwen", List.of(new ChatRequest.Message("user", "hi")), false, null, null);
    private static final ExecutionPolicies POLICIES = new ExecutionPolicies(
            60_000,
            TimeoutPolicy.defaults(),
            new RetryPolicy(1, true, true, 5_000, 100, 2_000, 0.2,
                    Set.of(429, 500, 502, 503, 504)),
            new CircuitBreakerConfig(100, 5, 0.5, 10_000, 0.5, 30_000, 1),
            new CooldownConfig(5, 30_000, 300_000, true));
    private static final Failure HTTP_5XX = new Failure(
            FailureType.HTTP_5XX, "上游 500", 500, true, -1);

    private final OpenAIConnector connector = mock(OpenAIConnector.class);
    private final CircuitBreakerRegistry circuitBreakers = mock(CircuitBreakerRegistry.class);
    private final CooldownManager cooldowns = mock(CooldownManager.class);
    private final RetryExecutor retryExecutor = mock(RetryExecutor.class);
    private final ExecutionPolicyManager policyManager = mock(ExecutionPolicyManager.class);
    private final ModelStateStore stateStore = mock(ModelStateStore.class);
    private final StateEventPipeline stateEvents = mock(StateEventPipeline.class);
    private final GatewayMetrics metrics = mock(GatewayMetrics.class);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private FallbackChainExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        when(policyManager.forRequest(anyString(), any())).thenReturn(POLICIES);
        when(circuitBreakers.allowRequest(anyString())).thenReturn(true);
        when(cooldowns.isCooling(anyString())).thenReturn(false);
        // mock RetryExecutor 转发给真实 Attempt（重试决策不在本测试范围）
        when(retryExecutor.execute(any(), any(), anyString(), any()))
                .thenAnswer(inv -> ((RetryExecutor.Attempt<?>) inv.getArgument(3)).run());
        executor = new FallbackChainExecutor(connector, circuitBreakers, cooldowns,
                retryExecutor, policyManager, stateStore, stateEvents, metrics, scheduler);
    }

    /** 主选失败 → 降级链上第二个候选返回结果，且打降级指标 */
    @Test
    void primaryFails_shouldDegradeToNextCandidate() throws Exception {
        when(connector.complete(CANDIDATE_A, REQUEST, POLICIES))
                .thenThrow(new UpstreamCallException(HTTP_5XX));
        ChatCompletion ok = mock(ChatCompletion.class);
        when(connector.complete(CANDIDATE_B, REQUEST, POLICIES)).thenReturn(ok);

        ChatResult result = executor.execute(
                List.of(CANDIDATE_A, CANDIDATE_B), REQUEST, "r1", Map.of());

        // V4：结果携带实际执行实例（计量用）
        assertThat(result.completion()).isSameAs(ok);
        assertThat(result.instance()).isSameAs(CANDIDATE_B);
        verify(metrics).fallback("mock-a:qwen-large", "HTTP_5XX");
        verify(connector).complete(CANDIDATE_B, REQUEST, POLICIES);
    }

    /** 熔断命中 → 候选被跳过、不发起上游调用；全部跳过 → 503 circuit_open */
    @Test
    void circuitOpen_shouldSkipCandidateAndThrow503() {
        when(circuitBreakers.allowRequest(anyString())).thenReturn(false);

        assertThatThrownBy(() -> executor.execute(
                List.of(CANDIDATE_A, CANDIDATE_B), REQUEST, "r1", Map.of()))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> {
                    GatewayException g = (GatewayException) e;
                    assertThat(g.getStatus()).isEqualTo(503);
                    assertThat(g.getType()).isEqualTo("circuit_open");
                });
        verify(connector, never()).complete(any(), any(), any());
    }

    /** 总时长预算用完 → 立即放弃，不再发起新尝试 → 504 timeout_total */
    @Test
    void totalTimeout_shouldReturn504() throws Exception {
        // totalMs=1，但首个候选真实耗时 10ms：预算在第一候选期间耗尽，
        // 第二次循环 guard.expired() → 立即放弃，不发起第二个候选
        ExecutionPolicies shortBudget = new ExecutionPolicies(
                1, POLICIES.timeout(), POLICIES.retry(),
                POLICIES.circuitBreaker(), POLICIES.cooldown());
        when(policyManager.forRequest(anyString(), any())).thenReturn(shortBudget);
        when(connector.complete(any(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(10);
            throw new UpstreamCallException(HTTP_5XX);
        });

        assertThatThrownBy(() -> executor.execute(
                List.of(CANDIDATE_A, CANDIDATE_B), REQUEST, "r1", Map.of()))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> {
                    GatewayException g = (GatewayException) e;
                    assertThat(g.getStatus()).isEqualTo(504);
                    assertThat(g.getType()).isEqualTo("timeout_total");
                });
        verify(connector, never()).complete(CANDIDATE_B, REQUEST, POLICIES);
    }
}
