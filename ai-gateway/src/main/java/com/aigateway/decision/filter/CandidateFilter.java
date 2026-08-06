package com.aigateway.decision.filter;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.plugin.context.PluginContext;

import java.util.List;

/**
 * 候选过滤器 SPI：对候选集做一次收窄（只允许移除，不允许新增）。
 * 加一种过滤规则 = 加一个 bean + order()，核心代码不动（与插件化同一个思想）。
 */
public interface CandidateFilter {

    /** 过滤器名（日志/指标维度） */
    String name();

    /** 执行顺序（小 → 大） */
    int order();

    List<ModelInstance> apply(ChatRequest request, PluginContext ctx, List<ModelInstance> candidates);
}
