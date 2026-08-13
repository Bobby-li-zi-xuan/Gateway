package com.aigateway.decision.filter;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.Capability;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.governance.meter.TokenEstimator;
import com.aigateway.plugin.context.PluginContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 第 1 道：能力过滤。
 * 请求所需能力（streaming / tools / vision）不在实例能力集内、或上下文窗口
 * 小于预估输入 token 的候选被淘汰。能力需求由 signals 的 required_capabilities
 * 提供（V2 决策引擎会在构建上下文时写入）。
 */
@Component
public class CapabilityFilter implements CandidateFilter {

    public int order() {
        return 50;
    }

    public String name() {
        return "capability";
    }

    public List<ModelInstance> apply(ChatRequest request, PluginContext ctx,
                                     List<ModelInstance> candidates) {
        // required_capabilities 由 DecisionEngine 在决策前写入
        Set<String> required = ctx.signals().getStringSet("required_capabilities").orElse(Set.of());
        int estimatedTokens = (int) TokenEstimator.estimateInputTokens(request,
                TokenEstimator.DEFAULT_CHARS_PER_TOKEN, TokenEstimator.DEFAULT_CJK_TOKEN_PER_CHAR);
        return candidates.stream()
                .filter(c -> capabilityOk(c, required, estimatedTokens))
                .toList();
    }

    private boolean capabilityOk(ModelInstance inst, Set<String> required, int tokens) {
        Capability cap = inst.capability();
        if (cap == null) {
            return true; // 未配置能力画像 = 不做限制
        }
        if (required.contains("streaming") && !cap.isStreaming()) return false;
        if (required.contains("tools") && !cap.isToolCallSupport()) return false;
        if (required.contains("vision") && !cap.isMultimodal()) return false;
        // contextLength <= 0 视为未配置（int 默认值 0），与 cap == null 的“不做限制”语义对齐
        return cap.getContextLength() <= 0 || cap.getContextLength() >= tokens;
    }
}
