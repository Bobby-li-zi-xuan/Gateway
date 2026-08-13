package com.aigateway.decision.scorer;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.decision.model.ModelState;
import com.aigateway.decision.model.ScoreFactors;
import com.aigateway.decision.model.ScoringDetail;
import com.aigateway.decision.state.ModelStateStore;
import com.aigateway.governance.meter.TokenEstimator;
import com.aigateway.infra.config.GatewayProperties;
import com.aigateway.plugin.context.Signals;
import com.aigateway.state.health.HealthChecker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多目标打分器：延迟 / 成本 / 质量 / 健康四因子，归一化后加权求和。
 *
 * 需要实现的关键行为：
 * 1. min-max 归一化（max==min 给满分，避免除零）；
 * 2. 动态调权（读 signals：task_complexity / budget_remaining_ratio），调整后重新归一化；
 * 3. 每个候选输出 raw / normalized / weights / finalScore 完整明细（可解释路由）。
 */
@Component
public class Scorer {

    private static final List<String> FACTORS = List.of("latency", "cost", "quality", "health");
    private static final Map<String, Double> DEFAULT_WEIGHTS = Map.of(
            "latency", 0.25, "cost", 0.25, "quality", 0.25, "health", 0.25);

    private final ModelStateStore stateStore;    // EWMA 延迟 / 错误率
    private final HealthChecker healthChecker;   // 健康快照
    private final GatewayProperties props;       // defaultOutputTokens 等

    public Scorer(ModelStateStore stateStore, HealthChecker healthChecker,
                  GatewayProperties props) {
        this.stateStore = stateStore;
        this.healthChecker = healthChecker;
        this.props = props;
    }

    /**
     * 对候选逐一打分，返回与入参同序的明细列表。
     *
     * 步骤：动态调权 → 收集原始因子 → min-max 归一化 → 加权求和 → 组装明细。
     */
    public List<ScoringDetail> score(List<ModelInstance> candidates,
                                     Map<String, Double> weights,
                                     Signals signals,
                                     ChatRequest request) {
        Map<String, Double> effective = normalizeWeights(
                adjustWeights(weights != null ? weights : DEFAULT_WEIGHTS, signals));

        // 1. 原始因子
        // 把候选实例列表 List<ModelInstance> 逐一对映成 List<ScoreFactors>
        // rawFactors 提取四项原始指标
        List<ScoreFactors> rawList = candidates.stream()
                .map(c -> rawFactors(c, request))
                .toList();

        // 2. 每列 min/max（同一批候选内相对比较）
        // 对第 1 步收集的 rawList，按列（latency / cost / quality / health 各自独立）算出这一批候选里的最小值和最大值
        // 这 8 个值就是接下来 min-max 归一化公式 (v − min) / (max − min) 里的分母分子基准
        // 有了每列的 min/max，下一步才能把每个候选的原始值换算成 0~1 的相对分并加权汇总
        double minLat = rawList.stream().mapToDouble(ScoreFactors::latency).min().orElse(0);
        double maxLat = rawList.stream().mapToDouble(ScoreFactors::latency).max().orElse(0);
        double minCost = rawList.stream().mapToDouble(ScoreFactors::cost).min().orElse(0);
        double maxCost = rawList.stream().mapToDouble(ScoreFactors::cost).max().orElse(0);
        double minQ = rawList.stream().mapToDouble(ScoreFactors::quality).min().orElse(0);
        double maxQ = rawList.stream().mapToDouble(ScoreFactors::quality).max().orElse(0);
        double minH = rawList.stream().mapToDouble(ScoreFactors::health).min().orElse(0);
        double maxH = rawList.stream().mapToDouble(ScoreFactors::health).max().orElse(0);

        // 3. 归一化 + 加权求和 + 明细
        List<ScoringDetail> details = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            ScoreFactors raw = rawList.get(i);
            ScoreFactors norm = new ScoreFactors(
                    normalizeLowerBetter(raw.latency(), minLat, maxLat),
                    normalizeLowerBetter(raw.cost(), minCost, maxCost),
                    normalizeHigherBetter(raw.quality(), minQ, maxQ),
                    normalizeHigherBetter(raw.health(), minH, maxH));
            double score = norm.latency() * effective.getOrDefault("latency", 0.0)
                    + norm.cost() * effective.getOrDefault("cost", 0.0)
                    + norm.quality() * effective.getOrDefault("quality", 0.0)
                    + norm.health() * effective.getOrDefault("health", 0.0);
            details.add(new ScoringDetail(candidates.get(i).instanceId(),
                    raw, norm, Map.copyOf(effective), score));
        }
        return details;
    }

    /** 原始因子：延迟来自 EWMA；成本来自单价 × 预估 token；质量来自配置；健康来自快照 × 错误率 */
    private ScoreFactors rawFactors(ModelInstance inst, ChatRequest request) {
        ModelState state = stateStore.stateOf(inst.instanceId());
        double latency = state.ewmaLatencyMs();
        double cost = estimateCost(inst, request);
        double quality = inst.qualityScore();
        double health = healthChecker.isHealthy(inst.channelId())
                ? 1.0 - state.errorRate() : 0.0;
        return new ScoreFactors(latency, cost, quality, health);
    }

    /** 成本预估：input 用请求消息估算，output 用 maxTokens 或默认值（V4 换成真实计量） */
    private double estimateCost(ModelInstance inst, ChatRequest request) {
        long inputTokens = TokenEstimator.estimateInputTokens(request,
                TokenEstimator.DEFAULT_CHARS_PER_TOKEN, TokenEstimator.DEFAULT_CJK_TOKEN_PER_CHAR);
        int outputTokens = request.maxTokens() != null
                ? request.maxTokens() : props.getDecision().getDefaultOutputTokens();
        return inputTokens / 1000.0 * inst.priceIn()
                + outputTokens / 1000.0 * inst.priceOut();
    }

    /**
     * 动态调权：执行前读信号，按规则调整权重。
     * 规则示例：
     * - task_complexity=COMPLEX → 质量权重 +0.2（从 latency/cost 各扣 0.1）；
     * - budget_remaining_ratio < 0.3 → 成本权重 +0.2（V4 真正写入该信号）。
     * 调整后必须重新归一化（权重和恒为 1）。
     */
    private Map<String, Double> adjustWeights(Map<String, Double> weights, Signals signals) {
        Map<String, Double> w = new HashMap<>(weights);

        if (signals.get("task_complexity").map("COMPLEX"::equals).orElse(false)) {
            w.computeIfPresent("quality", (k, v) -> v + 0.2);
            w.computeIfPresent("latency", (k, v) -> Math.max(0.0, v - 0.1));
            w.computeIfPresent("cost", (k, v) -> Math.max(0.0, v - 0.1));
        }
        if (signals.get("budget_remaining_ratio")
                .map(v -> ((Number) v).doubleValue() < 0.3).orElse(false)) {
            w.computeIfPresent("cost", (k, v) -> v + 0.2);
            w.computeIfPresent("quality", (k, v) -> Math.max(0.0, v - 0.1));
            w.computeIfPresent("health", (k, v) -> Math.max(0.0, v - 0.1));
        }
        return w;
    }

    /** 权重归一化：保证和 = 1（防御性：动态调权后和可能漂移） */
    private Map<String, Double> normalizeWeights(Map<String, Double> w) {
        double sum = w.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum <= 0) {
            return DEFAULT_WEIGHTS;
        }
        Map<String, Double> out = new HashMap<>();
        w.forEach((k, v) -> out.put(k, v / sum));
        return Map.copyOf(out);
    }

    /** 越低越好：1 - 归一化位置；max==min 时全部给满分（无区分度） */
    private static double normalizeLowerBetter(double v, double min, double max) {
        if (max == min) return 1.0;
        return 1.0 - (v - min) / (max - min);
    }

    /** 越高越好：归一化位置；max==min 时全部给满分 */
    private static double normalizeHigherBetter(double v, double min, double max) {
        if (max == min) return 1.0;
        return (v - min) / (max - min);
    }
}
