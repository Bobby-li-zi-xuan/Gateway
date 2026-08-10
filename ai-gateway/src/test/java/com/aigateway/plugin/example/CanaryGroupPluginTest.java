package com.aigateway.plugin.example;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.plugin.context.PluginContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 金丝雀插件信号写入单测（P5）：
 * 空/缺失的分组实例配置必须写成“存在但为空”的白名单（配合 P1 的 fail-closed 语义），
 * 而不是产生 "null" 字符串白名单。
 */
class CanaryGroupPluginTest {

    private final CanaryGroupPlugin plugin = new CanaryGroupPlugin();

    private final ChatRequest request = new ChatRequest("qwen",
            List.of(new ChatRequest.Message("user", "你好")), null, null, null);

    private PluginContext context(String canaryGroup) {
        return new PluginContext(request, "req-1", "qwen",
                Map.of("canary_group", canaryGroup),
                List.of(instance("mock-a:stable"), instance("mock-a:canary")));
    }

    private ModelInstance instance(String id) {
        return new ModelInstance(id, "qwen", "mock-a", id.split(":")[1],
                1, null, 0.001, 0.002, 0.9, 1000L);
    }

    @Test
    void emptyCanaryInstances_shouldWritePresentButEmptyAllowlist() {
        plugin.configure(Map.of("stableInstances", List.of("mock-a:stable"),
                "canaryInstances", List.of()));

        PluginContext ctx = context("experimental");
        plugin.beforeDecision(ctx);

        // 信号存在但为空：PluginSignalFilter 将淘汰全部候选（fail-closed）
        assertThat(ctx.signals().getStringSet("allowed_instances")).contains(Set.of());
    }

    @Test
    void nullInstanceValue_shouldBeEmptySetNotNullString() {
        // 防御路径：正常流程 validate() 会拦截 null，此处直接调 configure 验证
        // toStringSet 对 null 不产生 "null" 字符串（Map.of 不允许 null 值，故用 HashMap）
        Map<String, Object> config = new HashMap<>();
        config.put("stableInstances", null);
        config.put("canaryInstances", List.of());
        plugin.configure(config);

        PluginContext ctx = context("stable");
        plugin.beforeDecision(ctx);

        assertThat(ctx.signals().getStringSet("allowed_instances")).contains(Set.of());
    }

    @Test
    void scalarConfigValue_shouldBeSingletonAllowlist() {
        plugin.configure(Map.of("stableInstances", "mock-a:stable",
                "canaryInstances", List.of("mock-a:canary")));

        PluginContext ctx = context("stable");
        plugin.beforeDecision(ctx);

        assertThat(ctx.signals().getStringSet("allowed_instances"))
                .contains(Set.of("mock-a:stable"));
    }

    @Test
    void stableGroup_shouldUseStableInstances() {
        plugin.configure(Map.of("stableInstances", List.of("mock-a:stable"),
                "canaryInstances", List.of("mock-a:canary")));

        PluginContext ctx = context("stable");
        plugin.beforeDecision(ctx);

        assertThat(ctx.signals().getString("canary_group")).contains("stable");
        assertThat(ctx.signals().getStringSet("allowed_instances"))
                .contains(Set.of("mock-a:stable"));
    }
}
