package com.aigateway.decision.filter;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.plugin.context.PluginContext;
import com.aigateway.state.health.HealthChecker;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 第 2 道：可用性过滤。
 * 渠道不健康（HealthChecker 快照）或权重 ≤ 0 的候选淘汰。
 * V3 追加熔断状态（打开/冷却中的实例在此剔除）。
 */
@Component
public class AvailabilityFilter implements CandidateFilter {

    private final HealthChecker healthChecker;

    public AvailabilityFilter(HealthChecker healthChecker) {
        this.healthChecker = healthChecker;
    }

    public int order() {
        return 40;
    }

    public String name() {
        return "availability";
    }

    public List<ModelInstance> apply(ChatRequest request, PluginContext ctx,
                                     List<ModelInstance> candidates) {
        return candidates.stream()
                .filter(c -> healthChecker.isHealthy(c.channelId()) && c.weight() > 0)
                .toList();
    }
}
