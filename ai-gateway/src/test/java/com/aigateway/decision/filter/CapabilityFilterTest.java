package com.aigateway.decision.filter;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.Capability;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.plugin.context.PluginContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 能力过滤单测。
 * 核心回归（P2）：contextLength 缺省（int 默认 0）时不得淘汰候选，
 * 与 cap == null 的“未配置不做限制”语义对齐。
 */
class CapabilityFilterTest {

    private final CapabilityFilter filter = new CapabilityFilter();

    /** 48 字符全英文 → 估算 12 tokens（4 字符/token） */
    private final ChatRequest request = new ChatRequest("qwen",
            List.of(new ChatRequest.Message("user",
                    "hello world hello world hello world hello world")),
            null, null, null);

    private ModelInstance instance(Capability capability) {
        return new ModelInstance("mock-a:qwen-large", "qwen", "mock-a", "qwen-large",
                1, capability, 0.001, 0.002, 0.9, 1000L);
    }

    private List<ModelInstance> filter(Capability capability, String... required) {
        List<ModelInstance> candidates = List.of(instance(capability));
        PluginContext ctx = new PluginContext(request, "req-1", "qwen", Map.of(), candidates);
        ctx.signals().set("required_capabilities", Set.of(required));
        return this.filter.apply(request, ctx, candidates);
    }

    private Capability capability(int contextLength, boolean streaming,
                                  boolean toolCall, boolean multimodal) {
        return new Capability(contextLength, multimodal, toolCall,
                "HIGH", "HIGH", "QUALITY_HIGH", streaming);
    }

    @Test
    void nullCapability_shouldKeep() {
        assertThat(filter(null)).hasSize(1);
    }

    @Test
    void unconfiguredContextLength_shouldKeep() {
        // contextLength=0 视为未配置 → 即使 tokens 估算 > 0 也不淘汰
        assertThat(filter(capability(0, true, true, true))).hasSize(1);
    }

    @Test
    void sufficientContextLength_shouldKeep() {
        assertThat(filter(capability(100, true, true, true))).hasSize(1);
    }

    @Test
    void insufficientContextLength_shouldDrop() {
        // 12 tokens > contextLength 10 → 淘汰
        assertThat(filter(capability(10, true, true, true))).isEmpty();
    }

    @Test
    void missingStreaming_shouldDrop() {
        assertThat(filter(capability(100, false, true, true), "streaming")).isEmpty();
    }

    @Test
    void missingToolCall_shouldDrop() {
        assertThat(filter(capability(100, true, false, true), "tools")).isEmpty();
    }

    @Test
    void missingVision_shouldDrop() {
        assertThat(filter(capability(100, true, true, false), "vision")).isEmpty();
    }

    @Test
    void noRequiredCapability_shouldKeepEvenIfLimited() {
        // 无 required_capabilities 信号时，只受 contextLength 约束
        assertThat(filter(capability(100, false, false, false))).hasSize(1);
    }
}
