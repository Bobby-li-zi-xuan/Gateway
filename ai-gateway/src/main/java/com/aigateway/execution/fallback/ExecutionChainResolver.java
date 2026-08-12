package com.aigateway.execution.fallback;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.decision.engine.DecisionEngine;
import com.aigateway.decision.engine.DecisionEngine.DecisionOutcome;
import com.aigateway.decision.log.DecisionLogStore;
import com.aigateway.decision.model.RoutingDecision;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 候选链来源（脚手架，代码段 S10a）：决策层输出主选 + 降级链，执行层只按序执行。
 *
 * ⚠️ 与计划代码段的差异：保留决策日志落库（decisionLogStore.record）——
 * V2 的 /v1/debug/decisions 依赖决策明细，V3 执行层改造后由本类补记，保证 V2 回归。
 * （计划代码段注入的 ModelRegistry / HealthChecker 在 V2 决策引擎内已消费，执行层不需要。）
 *
 * 熔断 / 冷却不在链生成时剔除（状态可能在链生成后变化），而在执行时逐候选检查（H5/H6）。
 */
@Component
public class ExecutionChainResolver {

    private final DecisionEngine decisionEngine; // V2 已合并：决策输出主选 + 降级链
    private final DecisionLogStore decisionLogStore; // V2 决策日志（/v1/debug/decisions 数据源）

    public ExecutionChainResolver(DecisionEngine decisionEngine, DecisionLogStore decisionLogStore) {
        this.decisionEngine = decisionEngine;
        this.decisionLogStore = decisionLogStore;
    }

    /**
     * 生成本次请求的执行链：DecisionEngine 出主选 + 降级链（决策与执行分离）。
     */
    public List<ModelInstance> resolve(ChatRequest request, String requestId,
                                       Map<String, String> metadata) {
        DecisionOutcome outcome = decisionEngine.decide(request, requestId, metadata);
        // 决策明细先落库（V2 语义保留）：执行结果与决策一一对应
        decisionLogStore.record(outcome.decision());
        RoutingDecision d = outcome.decision();
        return concat(d.primary(), d.fallbackChain());
    }

    /** 主选 + 降级链拼接为执行链（决策层已完成健康过滤等收窄） */
    private List<ModelInstance> concat(ModelInstance primary, List<ModelInstance> fallback) {
        return Stream.concat(Stream.of(primary), fallback.stream()).toList();
    }
}
