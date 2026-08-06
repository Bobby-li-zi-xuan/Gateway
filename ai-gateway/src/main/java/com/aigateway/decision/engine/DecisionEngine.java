package com.aigateway.decision.engine;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.decision.filter.FilterChain;
import com.aigateway.decision.model.RoutingDecision;
import com.aigateway.decision.policy.PolicyManager;
import com.aigateway.decision.scheduler.Scheduler;
import com.aigateway.decision.scorer.Scorer;
import com.aigateway.observability.GatewayMetrics;
import com.aigateway.plugin.context.PluginContext;
import com.aigateway.plugin.engine.PluginEngine;
import com.aigateway.state.registry.ModelRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 决策引擎：把“插件 → 过滤 → 打分 → 调度”串成一条流水线，
 * 输出不可变的 RoutingDecision。
 *
 * 🖊 手敲 H6：本类为必手敲模块，按《版本2-详细实施计划》第 13 节实现。
 * 未完成前调用 {@link #decide} 会直接报错。
 *
 * 流水线：beforeDecision 插件（H1）→ FilterChain（H2）→ Scorer（H3）
 * → Scheduler（H4）→ afterDecision 插件 → 指标 + 日志。
 */
@Component
public class DecisionEngine {

    private final ModelRegistry registry;
    private final PluginEngine pluginEngine;
    private final FilterChain filterChain;
    private final Scorer scorer;
    private final Scheduler scheduler;
    private final PolicyManager policyManager;
    private final GatewayMetrics metrics;

    public DecisionEngine(ModelRegistry registry, PluginEngine pluginEngine,
                          FilterChain filterChain, Scorer scorer,
                          Scheduler scheduler, PolicyManager policyManager,
                          GatewayMetrics metrics) {
        this.registry = registry;
        this.pluginEngine = pluginEngine;
        this.filterChain = filterChain;
        this.scorer = scorer;
        this.scheduler = scheduler;
        this.policyManager = policyManager;
        this.metrics = metrics;
    }

    /** 决策结果 + 插件上下文（执行钩子还需要信号与元数据） */
    public record DecisionOutcome(RoutingDecision decision, PluginContext context) {}

    /** TODO H6：按《版本2-详细实施计划》第 13 节手敲实现 */
    public DecisionOutcome decide(ChatRequest request, String requestId,
                                  Map<String, String> metadata) {
        throw new GatewayException(500, "not_implemented",
                "TODO H6：DecisionEngine 未实现（按《版本2-详细实施计划》第 13 节手敲）");
    }
}
