package com.aigateway.decision.filter;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.Capability;
import com.aigateway.core.domain.model.ModelInstance;
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
        int estimatedTokens = estimateInputTokens(request);
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

    /** 输入 token 估算：逐条消息按文本折算后求和，至少 1（口径与 mock、版本4 计量 4.5 一致） */
    private static int estimateInputTokens(ChatRequest request) {
        if (request.messages() == null) return 1;
        int tokens = request.messages().stream()
                .map(ChatRequest.Message::content)
                .filter(s -> s != null)
                .mapToInt(CapabilityFilter::estimateTextTokens)
                .sum();
        return Math.max(1, tokens);
    }

    /** 文本 → token 近似：CJK 字符约 1 字/token，其余约 4 字符/token（BPE 实际中文 1~2 token/字，故统一按 1 计为下限口径） */
    private static int estimateTextTokens(String text) {
        int cjk = 0, other = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) cjk++;
            else other++;
        }
        return cjk + (other + 3) / 4;
    }
}
