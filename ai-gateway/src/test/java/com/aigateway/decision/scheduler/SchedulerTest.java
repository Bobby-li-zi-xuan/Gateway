package com.aigateway.decision.scheduler;

import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.decision.model.Condition;
import com.aigateway.decision.model.ConditionRule;
import com.aigateway.decision.model.Policy;
import com.aigateway.decision.model.RoutingDecision;
import com.aigateway.decision.model.ScoringDetail;
import com.aigateway.decision.policy.PolicyManager;
import com.aigateway.plugin.context.Signals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 调度器单测（计划 20.1，H4）：
 * 多目标排序 / 加权随机宽松区间 / 条件命中与未命中回退。
 */
class SchedulerTest {

    private final PolicyManager policyManager = mock(PolicyManager.class);
    private final WeightedRandomStrategy weightedRandom = new WeightedRandomStrategy();
    private final Scheduler scheduler = new Scheduler(policyManager,
            new MultiObjectiveStrategy(), weightedRandom, new ConditionalStrategy(weightedRandom));

    private final Policy balanced = new Policy("balanced", Policy.PolicyType.MULTI_OBJECTIVE,
            Map.of("latency", 0.25, "cost", 0.25, "quality", 0.25, "health", 0.25),
            List.of(), null);

    private ModelInstance instance(String id, int weight) {
        return new ModelInstance(id, "qwen", id.split(":")[0], id.split(":")[1],
                weight, null, 0.001, 0.002, 0.9, 1000L);
    }

    private ScoringDetail detail(String instanceId, double score) {
        return new ScoringDetail(instanceId, null, null, Map.of(), score);
    }

    @BeforeEach
    void setUp() {
        when(policyManager.byName("balanced")).thenReturn(balanced);
    }

    @Test
    void multiObjective_shouldPickHighestScoreAsPrimaryAndSortFallback() {
        List<ModelInstance> candidates = List.of(
                instance("mock-a:qwen-large", 1), instance("mock-b:qwen-small", 1));
        List<ScoringDetail> details = List.of(
                detail("mock-b:qwen-small", 0.9), detail("mock-a:qwen-large", 0.1));

        RoutingDecision decision = scheduler.decide("req-1", "qwen", balanced,
                candidates, details, new Signals());

        assertThat(decision.primary().instanceId()).isEqualTo("mock-b:qwen-small");
        assertThat(decision.fallbackChain()).extracting(ModelInstance::instanceId)
                .containsExactly("mock-a:qwen-large");
        assertThat(decision.conditionalRuleHit()).isNull();
    }

    @Test
    void weightedRandom_shouldLandInLooseInterval() {
        // 权重 8:2，跑 1000 次落在宽松区间（75%~85%），避免 flaky
        Policy random = new Policy("qwen-proportional", Policy.PolicyType.WEIGHTED_RANDOM,
                Map.of(), List.of(), null);
        List<ModelInstance> candidates = List.of(
                instance("mock-a:qwen-large", 8), instance("mock-b:qwen-small", 2));

        int hits = 0;
        for (int i = 0; i < 1000; i++) {
            RoutingDecision d = scheduler.decide("req-" + i, "qwen", random,
                    candidates, List.of(), new Signals());
            if (d.primary().instanceId().equals("mock-a:qwen-large")) hits++;
        }

        assertThat(hits).isBetween(750, 850);
    }

    @Test
    void conditionalHit_shouldNarrowCandidatesAndRecordRuleHit() {
        Policy conditional = new Policy("simple-task", Policy.PolicyType.CONDITIONAL,
                Map.of(), List.of(new ConditionRule(
                        new Condition("task_complexity", Condition.Op.EQ, "COMPLEX"),
                        List.of("mock-a:qwen-large"))), "balanced");
        Signals signals = new Signals();
        signals.set("task_complexity", "COMPLEX");
        List<ModelInstance> candidates = List.of(
                instance("mock-a:qwen-large", 8), instance("mock-b:qwen-small", 2));

        Policy effective = scheduler.effectivePolicy("qwen", conditional, signals);
        assertThat(effective).isSameAs(conditional); // 命中返回自身

        RoutingDecision decision = scheduler.decide("req-1", "qwen", effective,
                candidates, List.of(), signals);

        assertThat(decision.primary().instanceId()).isEqualTo("mock-a:qwen-large");
        assertThat(decision.conditionalRuleHit()).contains("rule#0:task_complexity=COMPLEX");
    }

    @Test
    void conditionalMiss_shouldFallbackToDefaultStrategy() {
        Policy conditional = new Policy("simple-task", Policy.PolicyType.CONDITIONAL,
                Map.of(), List.of(new ConditionRule(
                        new Condition("task_complexity", Condition.Op.EQ, "COMPLEX"),
                        List.of("mock-a:qwen-large"))), "balanced");
        Signals signals = new Signals(); // 无 task_complexity 信号

        Policy effective = scheduler.effectivePolicy("qwen", conditional, signals);

        // 未命中回退 defaultStrategy（MULTI_OBJECTIVE），其权重用于打分与分派
        assertThat(effective).isSameAs(balanced);
        assertThat(effective.type()).isEqualTo(Policy.PolicyType.MULTI_OBJECTIVE);
    }

    @Test
    void conditionalHitWithEmptySelect_shouldContinueToNextRule() {
        // 信号满足但目标候选已被过滤链移除 → 该规则视为未命中，继续求值下一条
        Policy conditional = new Policy("simple-task", Policy.PolicyType.CONDITIONAL,
                Map.of(), List.of(
                        new ConditionRule(new Condition("task_complexity", Condition.Op.EQ, "COMPLEX"),
                                List.of("mock-c:gone")),
                        new ConditionRule(new Condition("task_complexity", Condition.Op.EQ, "COMPLEX"),
                                List.of("mock-b:qwen-small"))), "balanced");
        Signals signals = new Signals();
        signals.set("task_complexity", "COMPLEX");
        List<ModelInstance> candidates = List.of(
                instance("mock-a:qwen-large", 8), instance("mock-b:qwen-small", 2));

        Policy effective = scheduler.effectivePolicy("qwen", conditional, signals);
        assertThat(effective).isSameAs(conditional);

        RoutingDecision decision = scheduler.decide("req-1", "qwen", effective,
                candidates, List.of(), signals);

        assertThat(decision.primary().instanceId()).isEqualTo("mock-b:qwen-small");
        assertThat(decision.conditionalRuleHit()).contains("rule#1");
    }
}
