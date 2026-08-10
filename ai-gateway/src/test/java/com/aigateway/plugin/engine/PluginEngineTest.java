package com.aigateway.plugin.engine;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.plugin.context.PluginContext;
import com.aigateway.plugin.registry.PluginRegistration;
import com.aigateway.plugin.registry.PluginRegistry;
import com.aigateway.plugin.spi.GatewayPlugin;
import com.aigateway.plugin.spi.PluginPhase;
import com.aigateway.plugin.spi.PluginScope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 插件执行引擎单测（计划 20.1，H1）：
 * 顺序 / 作用域 / 链式可见 / fail-closed / 异常包装。
 */
class PluginEngineTest {

    private final ChatRequest request = new ChatRequest("qwen",
            List.of(new ChatRequest.Message("user", "hi")), null, null, null);

    private ModelInstance instance(String id) {
        return new ModelInstance(id, "qwen", "mock-a", id.split(":")[1],
                1, null, 0.001, 0.002, 0.9, 1000L);
    }

    private PluginEngine engine(PluginRegistration... regs) {
        PluginRegistry registry = mock(PluginRegistry.class);
        when(registry.all()).thenReturn(List.of(regs));
        return new PluginEngine(registry);
    }

    private PluginRegistration reg(String name, int order, PluginScope scope,
                                  String scopeValue, GatewayPlugin plugin) {
        return new PluginRegistration(name, scope, scopeValue, order, Map.of(), plugin);
    }

    private PluginContext context(String alias, String... ids) {
        List<ModelInstance> candidates = List.of(ids).stream().map(this::instance).toList();
        return new PluginContext(request, "req-1", alias, Map.of(), candidates);
    }

    @Test
    void plugins_shouldRunInOrderDescending() {
        List<String> order = new ArrayList<>();
        GatewayPlugin low = new GatewayPlugin() {
            public String name() { return "low"; }
            public void beforeDecision(PluginContext ctx) { order.add("low"); }
        };
        GatewayPlugin high = new GatewayPlugin() {
            public String name() { return "high"; }
            public void beforeDecision(PluginContext ctx) { order.add("high"); }
        };
        PluginEngine engine = engine(
                reg("low", 1, PluginScope.GLOBAL, "", low),
                reg("high", 10, PluginScope.GLOBAL, "", high));

        engine.runPhase(PluginPhase.BEFORE_DECISION, context("qwen", "mock-a:qwen-large"));

        assertThat(order).containsExactly("high", "low");
    }

    @Test
    void routeScope_shouldOnlyMatchAlias() {
        List<String> touched = new ArrayList<>();
        GatewayPlugin route = new GatewayPlugin() {
            public String name() { return "route"; }
            public void beforeDecision(PluginContext ctx) { touched.add(ctx.alias()); }
        };
        PluginEngine engine = engine(reg("route", 1, PluginScope.ROUTE, "qwen", route));

        engine.runPhase(PluginPhase.BEFORE_DECISION, context("other", "mock-a:qwen-large"));

        assertThat(touched).isEmpty();
    }

    @Test
    void laterPlugin_shouldSeeEarlierSignalsAndNarrowedCandidates() {
        GatewayPlugin writer = new GatewayPlugin() {
            public String name() { return "writer"; }
            public void beforeDecision(PluginContext ctx) {
                ctx.signals().set("task_complexity", "COMPLEX");
                ctx.candidates().removeIf(c -> c.instanceId().equals("mock-b:qwen-small"));
            }
        };
        PluginEngine engine = engine(reg("writer", 10, PluginScope.GLOBAL, "", writer));

        PluginContext ctx = context("qwen", "mock-a:qwen-large", "mock-b:qwen-small");
        engine.runPhase(PluginPhase.BEFORE_DECISION, ctx);

        assertThat(ctx.signals().get("task_complexity")).contains("COMPLEX");
        assertThat(ctx.candidateIds()).containsExactly("mock-a:qwen-large");
    }

    @Test
    void clearedCandidates_shouldRejectWithCandidatesClearedAndStopChain() {
        List<String> touched = new ArrayList<>();
        GatewayPlugin clearer = new GatewayPlugin() {
            public String name() { return "clear"; }
            public void beforeDecision(PluginContext ctx) { ctx.candidates().clear(); }
        };
        GatewayPlugin after = new GatewayPlugin() {
            public String name() { return "after"; }
            public void beforeDecision(PluginContext ctx) { touched.add("after"); }
        };
        PluginEngine engine = engine(
                reg("clear", 10, PluginScope.GLOBAL, "", clearer),
                reg("after", 1, PluginScope.GLOBAL, "", after));

        assertThatThrownBy(() -> engine.runPhase(PluginPhase.BEFORE_DECISION,
                context("qwen", "mock-a:qwen-large", "mock-b:qwen-small")))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> {
                    assertThat(((GatewayException) e).getStatus()).isEqualTo(503);
                    assertThat(((GatewayException) e).getType()).isEqualTo("candidates_cleared");
                    assertThat(e.getMessage()).contains("clear");
                });
        assertThat(touched).isEmpty(); // 后续插件不再执行
    }

    @Test
    void pluginThrows_shouldWrapAsPluginError() {
        GatewayPlugin broken = new GatewayPlugin() {
            public String name() { return "broken"; }
            public void beforeDecision(PluginContext ctx) {
                throw new IllegalStateException("boom");
            }
        };
        PluginEngine engine = engine(reg("broken", 1, PluginScope.GLOBAL, "", broken));

        assertThatThrownBy(() -> engine.runPhase(PluginPhase.BEFORE_DECISION,
                context("qwen", "mock-a:qwen-large")))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> {
                    assertThat(((GatewayException) e).getStatus()).isEqualTo(500);
                    assertThat(((GatewayException) e).getType()).isEqualTo("plugin_error");
                    assertThat(e.getMessage()).contains("broken").contains("BEFORE_DECISION");
                });
    }

    @Test
    void clearedCandidatesAfterDecision_shouldNotFail() {
        // 执行阶段不检查候选是否为空——候选收窄是决策前的语义
        GatewayPlugin clearer = new GatewayPlugin() {
            public String name() { return "clear"; }
            public void beforeExecution(PluginContext ctx) { ctx.candidates().clear(); }
        };
        PluginEngine engine = engine(reg("clear", 1, PluginScope.GLOBAL, "", clearer));

        engine.runPhase(PluginPhase.BEFORE_EXECUTION,
                context("qwen", "mock-a:qwen-large"));
    }
}
