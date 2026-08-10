package com.aigateway.decision.filter;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.plugin.context.PluginContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 第 5 道：插件信号过滤。
 * 统一消费插件写入的 allowed_instances（白名单）与 blocked_instances（黑名单）信号，
 * 金丝雀等插件信号的收窄在此落地（LiteLLM 式“插件收窄候选”的声明式补充）。
 */
@Component
public class PluginSignalFilter implements CandidateFilter {

    public int order() {
        return 10;
    }

    public String name() {
        return "plugin-signal";
    }

    public List<ModelInstance> apply(ChatRequest request, PluginContext ctx,
                                     List<ModelInstance> candidates) {
        // 以“信号是否存在”而非“集合是否为空”判断：插件写入空白名单（如金丝雀分组
        // 未配置实例）意味着“一个都不允许”，此时若按 isEmpty 放行会造成 fail-open。
        Optional<Set<String>> allowed = ctx.signals().getStringSet("allowed_instances");
        Optional<Set<String>> blocked = ctx.signals().getStringSet("blocked_instances");
        if (allowed.isEmpty() && blocked.isEmpty()) {
            return candidates;
        }
        return candidates.stream()
                .filter(c -> (allowed.isEmpty() || allowed.get().contains(c.instanceId()))
                        && (blocked.isEmpty() || !blocked.get().contains(c.instanceId())))
                .toList();
    }
}
