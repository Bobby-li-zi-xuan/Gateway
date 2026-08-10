package com.aigateway.decision.filter;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.plugin.context.PluginContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 插件信号过滤单测：白名单/黑名单收窄语义。
 * 核心回归（P1）：allowed_instances 信号“存在但为空”必须 fail-closed（全部淘汰），
 * 而不是被当作“无信号”放行全部候选。
 */
class PluginSignalFilterTest {

    private final PluginSignalFilter filter = new PluginSignalFilter();

    private final ChatRequest request = new ChatRequest("qwen",
            List.of(new ChatRequest.Message("user", "你好")), null, null, null);

    private final List<ModelInstance> candidates = List.of(
            instance("mock-a:qwen-large"),
            instance("mock-a:qwen-small"));

    private ModelInstance instance(String id) {
        return new ModelInstance(id, "qwen", "mock-a", id.split(":")[1],
                1, null, 0.001, 0.002, 0.9, 1000L);
    }

    private PluginContext context() {
        return new PluginContext(request, "req-1", "qwen", Map.of(), candidates);
    }

    @Test
    void noSignal_shouldKeepAll() {
        assertThat(filter.apply(request, context(), candidates)).hasSize(2);
    }

    @Test
    void nonEmptyAllowlist_shouldNarrow() {
        PluginContext ctx = context();
        ctx.signals().set("allowed_instances", Set.of("mock-a:qwen-large"));

        List<ModelInstance> result = filter.apply(request, ctx, candidates);

        assertThat(result).extracting(ModelInstance::instanceId)
                .containsExactly("mock-a:qwen-large");
    }

    @Test
    void presentButEmptyAllowlist_shouldFailClosed() {
        // 金丝雀 experimental 组未配置任何实例：白名单存在但为空 → 全部淘汰
        PluginContext ctx = context();
        ctx.signals().set("allowed_instances", Set.of());

        assertThat(filter.apply(request, ctx, candidates)).isEmpty();
    }

    @Test
    void blocklist_shouldRemoveBlocked() {
        PluginContext ctx = context();
        ctx.signals().set("blocked_instances", Set.of("mock-a:qwen-small"));

        List<ModelInstance> result = filter.apply(request, ctx, candidates);

        assertThat(result).extracting(ModelInstance::instanceId)
                .containsExactly("mock-a:qwen-large");
    }

    @Test
    void presentButEmptyBlocklist_shouldKeepAll() {
        PluginContext ctx = context();
        ctx.signals().set("blocked_instances", Set.of());

        assertThat(filter.apply(request, ctx, candidates)).hasSize(2);
    }

    @Test
    void allowlistAndBlocklist_shouldCombine() {
        PluginContext ctx = context();
        ctx.signals().set("allowed_instances", Set.of("mock-a:qwen-large", "mock-a:qwen-small"));
        ctx.signals().set("blocked_instances", Set.of("mock-a:qwen-small"));

        List<ModelInstance> result = filter.apply(request, ctx, candidates);

        assertThat(result).extracting(ModelInstance::instanceId)
                .containsExactly("mock-a:qwen-large");
    }
}
