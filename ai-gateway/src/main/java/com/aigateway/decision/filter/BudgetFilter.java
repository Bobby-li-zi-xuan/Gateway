package com.aigateway.decision.filter;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.plugin.context.PluginContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 第 4 道：预算过滤（V4 占位）。
 * 版本 4 接入真实预算后：超出预算的候选淘汰或降权
 * （设计文档 6.1 / Envoy AI Gateway budget 语义）。当前直接放行。
 */
@Component
public class BudgetFilter implements CandidateFilter {

    public int order() {
        return 20;
    }

    public String name() {
        return "budget";
    }

    public List<ModelInstance> apply(ChatRequest request, PluginContext ctx,
                                     List<ModelInstance> candidates) {
        // TODO V4：接入真实预算后在此淘汰/降权；当前占位放行
        return candidates;
    }
}
