package com.aigateway.decision.filter;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.observability.GatewayMetrics;
import com.aigateway.plugin.context.PluginContext;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 过滤链：按 order 依次执行五道关卡（能力/可用性/策略/预算/插件信号）。
 *
 * 🖊 手敲 H2：本类为必手敲模块，按《版本2-详细实施计划》第 9.3 节实现。
 * 未完成前调用 {@link #apply} 会直接报错。
 *
 * 需要实现的关键行为：
 * 1. 只收窄、不扩大：过滤器的返回值不得包含入参里没有的候选；
 * 2. 淘汰计数写入指标与日志（可解释“为什么少了候选”）；
 * 3. 任一道把候选清空 → 503 no_available_model（fail-closed，不回退到过滤前的候选集）。
 */
@Component
public class FilterChain {

    private final List<CandidateFilter> filters; // 按 order 排序后的不可变链
    private final GatewayMetrics metrics;

    public FilterChain(List<CandidateFilter> filters, GatewayMetrics metrics) {
        this.filters = filters.stream()
                .sorted(Comparator.comparingInt(CandidateFilter::order))
                .toList();
        this.metrics = metrics;
    }

    /** TODO H2：按《版本2-详细实施计划》第 9.3 节手敲实现 */
    public List<ModelInstance> apply(ChatRequest request, PluginContext ctx,
                                     List<ModelInstance> candidates) {
        throw new GatewayException(500, "not_implemented",
                "TODO H2：FilterChain 未实现（按《版本2-详细实施计划》第 9.3 节手敲）");
    }
}
