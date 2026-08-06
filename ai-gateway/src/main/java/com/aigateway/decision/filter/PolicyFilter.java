package com.aigateway.decision.filter;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.plugin.context.PluginContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 第 3 道：策略过滤（令牌/租户模型范围）。
 * V2 为占位：读 signals 的 allowed_aliases（存在则按别名白名单收窄），
 * V4 接入真实令牌后由鉴权插件写入该信号。
 */
@Component
public class PolicyFilter implements CandidateFilter {

    public int order() {
        return 30;
    }

    public String name() {
        return "policy";
    }

    public List<ModelInstance> apply(ChatRequest request, PluginContext ctx,
                                     List<ModelInstance> candidates) {
        Optional<Set<String>> allowed = ctx.signals().getStringSet("allowed_aliases");
        if (allowed.isEmpty()) {
            return candidates;
        }
        Set<String> scope = allowed.get();
        return candidates.stream()
                .filter(c -> scope.contains(c.alias()))
                .toList();
    }
}
