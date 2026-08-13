package com.aigateway.decision.filter;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.governance.channel.ChannelStore;
import com.aigateway.plugin.context.PluginContext;
import com.aigateway.state.health.HealthChecker;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 第 2 道：可用性过滤。
 * 渠道不健康（HealthChecker 快照）、渠道停用/软删除（V4 ChannelStore）或权重 ≤ 0 的候选淘汰。
 */
@Component
public class AvailabilityFilter implements CandidateFilter {

    private final HealthChecker healthChecker;
    private final ChannelStore channelStore;

    public AvailabilityFilter(HealthChecker healthChecker, ChannelStore channelStore) {
        this.healthChecker = healthChecker;
        this.channelStore = channelStore;
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
                .filter(c -> channelStore.isEnabled(c.channelId())   // V4：渠道启停（DB 可覆盖配置）
                        && healthChecker.isHealthy(c.channelId())
                        && c.weight() > 0)
                .toList();
    }
}
