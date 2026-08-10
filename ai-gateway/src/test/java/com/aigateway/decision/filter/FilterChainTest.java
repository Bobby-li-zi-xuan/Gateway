package com.aigateway.decision.filter;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.observability.GatewayMetrics;
import com.aigateway.plugin.context.PluginContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.eq;

/**
 * 过滤链编排单测（计划 20.1，H2）：
 * 按 order 执行 / 信号白名单收窄 / 过滤清空 → 503 / filterDrops 计数。
 */
class FilterChainTest {

    private final GatewayMetrics metrics = mock(GatewayMetrics.class);
    private final PluginSignalFilter signalFilter = new PluginSignalFilter();

    private final ChatRequest request = new ChatRequest("qwen",
            List.of(new ChatRequest.Message("user", "hi")), null, null, null);

    private ModelInstance instance(String id) {
        return new ModelInstance(id, "qwen", "mock-a", id.split(":")[1],
                1, null, 0.001, 0.002, 0.9, 1000L);
    }

    private PluginContext context(List<ModelInstance> candidates) {
        return new PluginContext(request, "req-1", "qwen", Map.of(), candidates);
    }

    /** 记录执行顺序的哑过滤器 */
    private CandidateFilter orderRecording(String name, int order, List<String> log) {
        return new CandidateFilter() {
            public String name() { return name; }
            public int order() { return order; }
            public List<ModelInstance> apply(ChatRequest r, PluginContext ctx,
                                             List<ModelInstance> candidates) {
                log.add(name);
                return candidates;
            }
        };
    }

    @Test
    void filters_shouldRunByOrderDescending() {
        List<String> log = new java.util.ArrayList<>();
        FilterChain chain = new FilterChain(List.of(
                orderRecording("low", 1, log), orderRecording("high", 50, log)), metrics);

        chain.apply(request, context(List.of(instance("mock-a:qwen-large"))), List.of(instance("mock-a:qwen-large")));

        assertThat(log).containsExactly("high", "low");
    }

    @Test
    void allowlistSignal_shouldNarrowCandidates() {
        FilterChain chain = new FilterChain(List.of(signalFilter), metrics);
        List<ModelInstance> candidates = List.of(
                instance("mock-a:qwen-large"), instance("mock-b:qwen-small"));
        PluginContext ctx = context(candidates);
        ctx.signals().set("allowed_instances", Set.of("mock-a:qwen-large"));

        List<ModelInstance> result = chain.apply(request, ctx, candidates);

        assertThat(result).extracting(ModelInstance::instanceId)
                .containsExactly("mock-a:qwen-large");
        verify(metrics).filterDrops(anyString(), anyString(), anyInt());
    }

    @Test
    void emptyAllowlist_shouldDropAllAndFailClosed() {
        FilterChain chain = new FilterChain(List.of(signalFilter), metrics);
        List<ModelInstance> candidates = List.of(
                instance("mock-a:qwen-large"), instance("mock-b:qwen-small"));
        PluginContext ctx = context(candidates);
        // 白名单"存在但为空"：一个都不允许（fail-closed，不按 isEmpty 放行）
        ctx.signals().set("allowed_instances", Set.of());

        assertThatThrownBy(() -> chain.apply(request, ctx, candidates))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> {
                    assertThat(((GatewayException) e).getStatus()).isEqualTo(503);
                    assertThat(((GatewayException) e).getType()).isEqualTo("no_available_model");
                    assertThat(e.getMessage()).contains("plugin-signal");
                });
        verify(metrics).filterDrops(anyString(), anyString(), eq(2));
    }

    @Test
    void filterClearsCandidates_shouldRejectWithoutFallback() {
        CandidateFilter killer = new CandidateFilter() {
            public String name() { return "killer"; }
            public int order() { return 50; }
            public List<ModelInstance> apply(ChatRequest r, PluginContext ctx,
                                             List<ModelInstance> candidates) {
                return List.of();
            }
        };
        FilterChain chain = new FilterChain(List.of(killer, signalFilter), metrics);
        List<ModelInstance> candidates = List.of(instance("mock-a:qwen-large"));

        // fail-closed：不回退到过滤前的候选集
        assertThatThrownBy(() -> chain.apply(request, context(candidates), candidates))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> {
                    assertThat(((GatewayException) e).getStatus()).isEqualTo(503);
                    assertThat(((GatewayException) e).getType()).isEqualTo("no_available_model");
                });
    }
}
