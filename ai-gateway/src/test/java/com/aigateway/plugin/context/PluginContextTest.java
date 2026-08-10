package com.aigateway.plugin.context;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 插件上下文候选收窄约束单测（P4）：
 * setCandidates 只允许收窄，传入包含原候选集之外实例的集合必须被拒绝。
 */
class PluginContextTest {

    private final ChatRequest request = new ChatRequest("qwen",
            List.of(new ChatRequest.Message("user", "你好")), null, null, null);

    private ModelInstance instance(String id) {
        return new ModelInstance(id, "qwen", "mock-a", id.split(":")[1],
                1, null, 0.001, 0.002, 0.9, 1000L);
    }

    @Test
    void setCandidatesWithSubset_shouldNarrow() {
        PluginContext ctx = new PluginContext(request, "req-1", "qwen", Map.of(),
                List.of(instance("mock-a:a"), instance("mock-a:b")));

        ctx.setCandidates(List.of(instance("mock-a:b")));

        assertThat(ctx.candidates()).extracting(ModelInstance::instanceId)
                .containsExactly("mock-a:b");
    }

    @Test
    void setCandidatesWithForeignInstance_shouldReject() {
        PluginContext ctx = new PluginContext(request, "req-1", "qwen", Map.of(),
                List.of(instance("mock-a:a")));

        assertThatThrownBy(() -> ctx.setCandidates(List.of(instance("mock-a:other"))))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("只允许收窄");
    }

    @Test
    void setCandidatesEmpty_shouldBeAllowed() {
        // 清空是合法收窄（fail-closed 前置）；引擎在决策前对空候选抛 503
        PluginContext ctx = new PluginContext(request, "req-1", "qwen", Map.of(),
                List.of(instance("mock-a:a")));

        ctx.setCandidates(List.of());

        assertThat(ctx.candidates()).isEmpty();
    }
}
