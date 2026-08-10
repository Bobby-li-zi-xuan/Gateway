package com.aigateway.decision.filter;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.observability.GatewayMetrics;
import com.aigateway.plugin.context.PluginContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
                .sorted(Comparator.comparingInt(CandidateFilter::order).reversed())
                .toList();
        this.metrics = metrics;
    }

    /**
     * 依次执行五道关卡；每道返回收窄后的候选集。
     *
     * 规则：
     * 1. 只收窄、不扩大：过滤器的返回值不得包含入参里没有的候选；
     * 2. 淘汰计数写入指标与日志（可解释“为什么少了候选”）；
     * 3. 任一道把候选清空 → 503 no_available_model（fail-closed，
     *    不回退到过滤前的候选集——策略不可被绕过）。
     */
    public List<ModelInstance> apply(ChatRequest request, PluginContext ctx,
                                     List<ModelInstance> candidates) {
        List<ModelInstance> current = new ArrayList<>(candidates);
        for(CandidateFilter filter : filters){
            List<ModelInstance> before = current;
            current = filter.apply(request, ctx, before);

            int dropped = before.size() - current.size();
            if(dropped > 0){
                metrics.filterDrops(ctx.alias(), filter.name(), dropped);
                log(ctx.requestId(), "候选过滤",
                        filter.name() + " 淘汰 " + dropped + " 个，剩余 " + current.size() + " 个");
            }

            if (current.isEmpty()) {
                throw new GatewayException(503, "no_available_model",
                        "候选经[" + filter.name() + "]过滤后为空");
            }
        }
        return current;
    }

    private static void log(String requestId, String event, String detail) {
        System.out.printf("[requestId=%s] event=%s detail=%s%n", requestId, event, detail);
    }
}
