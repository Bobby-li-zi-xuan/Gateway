package com.aigateway.decision.engine;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.decision.filter.CapabilityFilter;
import com.aigateway.decision.filter.FilterChain;
import com.aigateway.decision.model.Policy;
import com.aigateway.decision.model.RoutingDecision;
import com.aigateway.decision.model.ScoringDetail;
import com.aigateway.decision.policy.PolicyManager;
import com.aigateway.decision.scheduler.Scheduler;
import com.aigateway.decision.scorer.Scorer;
import com.aigateway.observability.GatewayMetrics;
import com.aigateway.plugin.context.PluginContext;
import com.aigateway.plugin.engine.PluginEngine;
import com.aigateway.plugin.spi.PluginPhase;
import com.aigateway.state.registry.ModelRegistry;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 决策引擎：把“插件 → 过滤 → 打分 → 调度”串成一条流水线，
 * 输出不可变的 RoutingDecision。
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

    /**
     * 一次请求的决策流水线：
     * beforeDecision 插件（信号 + 收窄 + fail-closed）
     *   → Filter Chain 五道关卡
     *   → Scorer 多目标打分（动态调权）
     *   → Scheduler 出方案（策略分派）
     *   → afterDecision 插件（只读决策）
     *   → 指标 + 日志
     */
    public DecisionOutcome decide(ChatRequest request, String requestId,
                                  Map<String, String> metadata) {
        List<ModelInstance> initial = registry.findByAlias(request.model());
        if (initial.isEmpty()) {
            throw new GatewayException(503, "no_available_model",
                    "模型[" + request.model() + "] 不存在或未配置候选");
        }

        // 1. 决策前插件：写信号、收窄候选；清空候选
        PluginContext ctx = new PluginContext(request, requestId, request.model(), metadata, initial);
        writeRequiredCapabilities(ctx);     //请求固有属性 -> 信号（能力过滤的输入）
        pluginEngine.runPhase(PluginPhase.BEFORE_DECISION, ctx);

        // 2.Filter Chain: 五道关卡收窄；清空
        List<ModelInstance> filtered = filterChain.apply(request, ctx, ctx.candidates());
        ctx.setCandidates(filtered);

        // 3. Score: 多目标打分。权重必须来自“有效策略”
        // CONDITIONAL 未命中时用 defaultStrategy的权重打分
        // 否则回退排序会按默认均衡权重算，策略配置不生效
        Policy policy = policyManager.resolve(request.model());
        Policy effective = scheduler.effectivePolicy(request.model(), policy, ctx.signals());
        List<ScoringDetail> details = scorer.score(filtered, effective.weights(), ctx.signals(), request);
    
        // 4. Scheduler: 出方案。分派必须用effective
        // 条件未命中时 effective 已是 defaultStrategy（MULTI_OBJECTIVE），
        // 分派才会落到多目标分支；传原始 policy 会让未命中触发条件分支的 503
        RoutingDecision decision = scheduler.decide(requestId, request.model(),
                effective, filtered, details, ctx.signals());
        ctx.setDecision(decision);

        // 5. 决策后插件：只读 decision（可写审计类信号）
        pluginEngine.runPhase(PluginPhase.AFTER_DECISION, ctx);

        metrics.decision(decision);
        logDecision(decision);
        return new DecisionOutcome(decision, ctx);
    }

    /** 请求固有属性 → required_capabilities 信号（CapabilityFilter 消费，见 S7）：
     *  V2 只写 streaming（request 有 stream 字段）；tools/vision 的请求字段 V1 未定义，
     *  逻辑为 V3 预留——信号不写，CapabilityFilter 相应分支即放行 */
    private void writeRequiredCapabilities(PluginContext ctx) {
        Set<String> required = new HashSet<>();
        if (ctx.request().streaming()) {
            required.add("streaming");
        }
        if (!required.isEmpty()) {
            ctx.signals().set("required_capabilities", required);
        }
    }

    private void logDecision(RoutingDecision decision) {
        System.out.printf("[requestId=%s] event=decision alias=%s strategy=%s primary=%s "
                        + "fallback=%s ruleHit=%s%n",
                decision.requestId(), decision.alias(), decision.strategy(),
                decision.primary().instanceId(),
                decision.fallbackChain().stream().map(ModelInstance::instanceId).toList(),
                decision.conditionalRuleHit());
    }
}
