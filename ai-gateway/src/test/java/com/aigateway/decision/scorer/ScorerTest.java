package com.aigateway.decision.scorer;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.decision.model.ModelState;
import com.aigateway.decision.model.ScoringDetail;
import com.aigateway.execution.model.CircuitState;
import com.aigateway.decision.state.ModelStateStore;
import com.aigateway.infra.config.GatewayProperties;
import com.aigateway.plugin.context.Signals;
import com.aigateway.state.health.HealthChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 多目标打分单测（计划 20.1，H3）：
 * min-max 归一化与 max==min 边界 / 动态调权 / 明细字段 / latency-first vs cost-first。
 */
class ScorerTest {

    private final ModelStateStore stateStore = mock(ModelStateStore.class);
    private final HealthChecker healthChecker = mock(HealthChecker.class);
    private final GatewayProperties props = new GatewayProperties();
    private Scorer scorer;

    private final ChatRequest request = new ChatRequest("qwen",
            List.of(new ChatRequest.Message("user", "hi")), null, null, null);

    /** a：延迟 2000、价格低（0.001/0.002）、质量 0.9；b：延迟 200、价格高（0.01/0.02）、质量 0.4 */
    private final ModelInstance a = instance("mock-a:qwen-large", 2000, 0.001, 0.002, 0.9);
    private final ModelInstance b = instance("mock-b:qwen-small", 200, 0.01, 0.02, 0.4);

    @BeforeEach
    void setUp() {
        scorer = new Scorer(stateStore, healthChecker, props);
        when(healthChecker.isHealthy(anyString())).thenReturn(true);
        when(stateStore.stateOf(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            long latency = id.contains("qwen-small") ? 200 : 2000;
            return new ModelState(id, latency, 0.0, 0, System.currentTimeMillis(),
                    CircuitState.CLOSED, 0, 0, false);
        });
    }

    private ModelInstance instance(String id, long latencyMs, double priceIn,
                                   double priceOut, double quality) {
        return new ModelInstance(id, "qwen", id.split(":")[0], id.split(":")[1],
                1, null, priceIn, priceOut, quality, latencyMs);
    }

    private List<ScoringDetail> score(Map<String, Double> weights, Signals signals) {
        return scorer.score(List.of(a, b), weights, signals, request);
    }

    @Test
    void lowerLatencyAndCost_shouldScoreHigherWhenWeightsFavorThem() {
        // 演示 B 的单元版：latency-first → b 分最高；cost-first → a 分最高。
        // 注意：Scorer.score 返回与入参同序的明细（不排序），最高分候选需自行比较
        List<ScoringDetail> latencyFirst = score(
                Map.of("latency", 0.6, "cost", 0.1, "quality", 0.15, "health", 0.15),
                new Signals());
        assertThat(bestOf(latencyFirst)).isEqualTo("mock-b:qwen-small");

        List<ScoringDetail> costFirst = score(
                Map.of("latency", 0.1, "cost", 0.6, "quality", 0.2, "health", 0.1),
                new Signals());
        assertThat(bestOf(costFirst)).isEqualTo("mock-a:qwen-large");
    }

    private static String bestOf(List<ScoringDetail> details) {
        return details.stream()
                .max(java.util.Comparator.comparingDouble(ScoringDetail::finalScore))
                .orElseThrow().instanceId();
    }

    @Test
    void identicalFactor_shouldGiveFullScoreToAll() {
        // 两个候选延迟相同 → max==min → 该因子全部满分，不除零
        List<ModelInstance> candidates = List.of(a, a);
        when(stateStore.stateOf(anyString())).thenReturn(
                new ModelState("x", 500, 0.0, 0, 0, CircuitState.CLOSED, 0, 0, false));

        List<ScoringDetail> details = scorer.score(candidates,
                Map.of("latency", 0.6, "cost", 0.1, "quality", 0.15, "health", 0.15),
                new Signals(), request);

        assertThat(details).hasSize(2);
        for (ScoringDetail d : details) {
            assertThat(d.normalized().latency()).isEqualTo(1.0);
        }
    }

    @Test
    void complexSignal_shouldRaiseQualityWeight() {
        // 动态调权：task_complexity=COMPLEX → quality +0.2（从 latency/cost 各扣 0.1），
        // 调整后重新归一化，权重和恒为 1
        Signals signals = new Signals();
        signals.set("task_complexity", "COMPLEX");

        List<ScoringDetail> details = score(
                Map.of("latency", 0.25, "cost", 0.25, "quality", 0.25, "health", 0.25),
                signals);

        double qualityWeight = details.get(0).weights().get("quality");
        assertThat(qualityWeight).isEqualTo(0.45);
        assertThat(details.get(0).weights().values().stream().mapToDouble(Double::doubleValue).sum())
                .isEqualTo(1.0);
    }

    @Test
    void details_shouldContainRawNormalizedWeightsAndFinalScore() {
        List<ScoringDetail> details = score(
                Map.of("latency", 0.6, "cost", 0.1, "quality", 0.15, "health", 0.15),
                new Signals());

        ScoringDetail d = details.get(0);
        assertThat(d.raw()).isNotNull();
        assertThat(d.normalized()).isNotNull();
        assertThat(d.weights()).containsKeys("latency", "cost", "quality", "health");
        // finalScore = Σ weight × normalized（四个因子各 ≥0，抽查加权和一致）
        double expected = d.normalized().latency() * d.weights().get("latency")
                + d.normalized().cost() * d.weights().get("cost")
                + d.normalized().quality() * d.weights().get("quality")
                + d.normalized().health() * d.weights().get("health");
        assertThat(d.finalScore()).isEqualTo(expected);
    }
}
