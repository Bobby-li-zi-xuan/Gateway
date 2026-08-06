package com.aigateway.decision.filter;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.plugin.context.PluginContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 第 5 道：插件信号过滤。
 * 统一消费插件写入的 allowed_instances（白名单）与 blocked_instances（黑名单）信号，
 * 金丝雀等插件信号的收窄在此落地（LiteLLM 式“插件收窄候选”的声明式补充）。
 */
@Component
public class PluginSignalFilter implements CandidateFilter {

    public int order() {
        return 50;
    }

    public String name() {
        return "plugin-signal";
    }

    public List<ModelInstance> apply(ChatRequest request, PluginContext ctx,
                                     List<ModelInstance> candidates) {
        Set<String> allowed = ctx.signals().getStringSet("allowed_instances").orElse(Set.of());
        Set<String> blocked = ctx.signals().getStringSet("blocked_instances").orElse(Set.of());
        if (allowed.isEmpty() && blocked.isEmpty()) {
            return candidates;
        }
        return candidates.stream()
                .filter(c -> (allowed.isEmpty() || allowed.contains(c.instanceId()))
                        && !blocked.contains(c.instanceId()))
                .toList();
    }
}
