package com.aigateway.state.health;

import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.infra.config.GatewayProperties;
import com.aigateway.state.registry.ModelRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 健康检查：定时探活 + 状态快照。
 *
 * 🖊 手敲 H4：状态转换逻辑（mark / isHealthy）尚未实现，
 * 请按《版本1-详细实施计划》第 13 节补全（见下方 TODO）。
 */
@Component
public class HealthChecker {

    private final GatewayProperties props;
    private final ModelRegistry registry;
    private final WebClient webClient;

    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private final Map<String, Boolean> healthy = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public HealthChecker(GatewayProperties props, ModelRegistry registry, WebClient webClient) {
        this.props = props;
        this.registry = registry;
        this.webClient = webClient;
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleAtFixedRate(this::checkAll, 0,
                props.getHealth().getIntervalSeconds(), TimeUnit.SECONDS);
    }

    private void checkAll() {
        registry.findAll().stream()
                .map(ModelInstance::channelId)
                .distinct()
                .forEach(this::checkOne);
    }

    private void checkOne(String channelId) {
        registry.findChannel(channelId).ifPresent(ch ->
                webClient.get()
                        .uri(ch.baseUrl() + "/health")
                        .retrieve()
                        .bodyToMono(Map.class)
                        .timeout(Duration.ofMillis(props.getHealth().getTimeoutMs()))
                        .subscribe(
                                body -> mark(channelId, "UP".equals(body.get("status"))),
                                err -> mark(channelId, false)
                        ));
    }

    // ============================================================
    // TODO H4（手敲）：状态转换
    // mark(channelId, up)：
    //   - up   → 连续失败计数清零，healthy[channelId] = true
    //   - !up  → 连续失败 +1；达到 props.getHealth().getConsecutiveFailures() 才置 false
    // isHealthy(channelId)：
    //   - 读 healthy 快照，默认 true（首次探活前放行）
    // ============================================================
    private void mark(String channelId, boolean up) {
        throw new UnsupportedOperationException(
                "H4 未实现：请手敲 mark() 状态转换逻辑（见详细实施计划第 13 节）");
    }

    public boolean isHealthy(String channelId) {
        throw new UnsupportedOperationException(
                "H4 未实现：请手敲 isHealthy() 快照读取（见详细实施计划第 13 节）");
    }
}
