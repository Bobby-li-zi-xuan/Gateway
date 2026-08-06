package com.aigateway.state.health;

import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.infra.config.GatewayProperties;
import com.aigateway.state.registry.ModelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 健康检查：定时探活（阻塞式探针）+ 状态快照。
 *
 * 工作方式：
 * - 启动后由 {@code @PostConstruct} 启动单线程定时任务，每隔 intervalSeconds 秒
 *   对每个渠道的 /health 端点做一次阻塞式 GET（checkAll → checkOne → probe）；
 * - 探活结果交给 {@link #mark} 更新“连续失败次数 + 健康快照”；
 * - 路由线程只读 {@link #isHealthy} 的内存快照，请求路径零阻塞。
 *
 * 🖊 手敲 H4：状态转换逻辑（mark / isHealthy）尚未实现，
 * 请按《版本1-详细实施计划》第 13 节补全（见下方 TODO）。
 *
 * 学习要点：
 * - {@link ConcurrentHashMap} 保证“定时线程写、路由线程读”之间的可见性；
 * - 状态粒度是渠道（V1），路由按渠道过滤；
 * - isHealthy 默认返回 true：首次探活完成前放行，避免启动瞬间误杀全部流量。
 */
@Component
public class HealthChecker {

    private final GatewayProperties props;      // 健康检查参数（间隔/超时/阈值）
    private final ModelRegistry registry;       // 渠道表（探活对象）
    private final HttpClient httpClient;        // 复用的 HTTP 客户端
    private final ObjectMapper objectMapper;    // 解析 /health 返回的 JSON

    /** 渠道 → 连续失败次数（写：定时线程；读：定时线程/路由线程） */
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    /** 渠道 → 是否健康的最新快照（路由线程读） */
    private final Map<String, Boolean> healthy = new ConcurrentHashMap<>();

    /** 单线程调度器：探活是阻塞调用，串行执行避免多个探针并发互相干扰 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public HealthChecker(GatewayProperties props, ModelRegistry registry,
                         HttpClient httpClient, ObjectMapper objectMapper) {
        this.props = props;
        this.registry = registry;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /** 应用启动后立即启动定时探活：首次立即执行，之后按 intervalSeconds 固定频率 */
    @PostConstruct
    public void start() {
        scheduler.scheduleAtFixedRate(this::checkAll, 0,
                props.getHealth().getIntervalSeconds(), TimeUnit.SECONDS);
    }

    /** 遍历所有渠道（按渠道 ID 去重），逐个探活并更新状态 */
    private void checkAll() {
        registry.findAll().stream()
                .map(ModelInstance::channelId)
                .distinct()
                .forEach(this::checkOne);
    }

    /** 渠道存在时：探活一次，并把结果交给状态转换逻辑 mark() */
    private void checkOne(String channelId) {
        registry.findChannel(channelId).ifPresent(ch ->
                mark(channelId, probe(ch.baseUrl())));
    }

    /**
     * 阻塞式探活：GET {baseUrl}/health，200 且响应体 status=UP 视为健康。
     * 任何异常（超时/连接失败/JSON 解析失败）一律视为不健康，由调用方决定怎么累计。
     */
    private boolean probe(String baseUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofMillis(props.getHealth().getTimeoutMs()))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            // 非 200 直接判不健康
            if (response.statusCode() != 200) {
                return false;
            }
            // 解析 {"status":"UP"}；Jackson 宽松读取，只关心 status 字段
            Map<?, ?> body = objectMapper.readValue(response.body(), Map.class);
            return "UP".equals(body.get("status"));
        } catch (Exception e) {
            // 网络异常/解析异常：保守判为不健康
            return false;
        }
    }

    // ============================================================
    // TODO H4（手敲）：状态转换
    // mark(channelId, up)：
    //   - up   → 连续失败计数清零，healthy[channelId] = true
    //   - !up  → 连续失败 +1；达到 props.getHealth().getConsecutiveFailures() 才置 false
    // isHealthy(channelId)：
    //   - 读 healthy 快照，默认 true（首次探活前放行）
    // 学习提示：为什么“连续失败达到阈值才置 false”？
    //   避免单次网络抖动就把渠道踢出路由；阈值给了服务恢复的缓冲。
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
