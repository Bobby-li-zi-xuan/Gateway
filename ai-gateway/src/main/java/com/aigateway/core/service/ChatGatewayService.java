package com.aigateway.core.service;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.api.dto.ChatCompletion;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.execution.connector.OpenAIConnector;
import com.aigateway.observability.GatewayMetrics;
import com.aigateway.state.health.HealthChecker;
import com.aigateway.state.registry.ModelRegistry;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 网关主流程：路由 + 转发 + 失败切换。
 *
 * 需要注入：ModelRegistry、HealthChecker、OpenAIConnector、GatewayMetrics。
 * 逻辑：findByAlias → 健康过滤（isHealthy）→ WeightedRandomPicker（H1）
 *       → connector 调用 → 失败换下一个候选（最多 maxAttempts 次）。
 */
@Service
public class ChatGatewayService {

    private final ModelRegistry registry;
    private final HealthChecker healthChecker;
    private final OpenAIConnector connector;
    private final GatewayMetrics metrics;
    private static final Logger LOG = LoggerFactory.getLogger(ChatGatewayService.class);

    public ChatGatewayService(ModelRegistry registry, HealthChecker healthChecker,
                              OpenAIConnector connector, GatewayMetrics metrics) {
        this.registry = registry;
        this.healthChecker = healthChecker;
        this.connector = connector;
        this.metrics = metrics;
    }

    // 非流式：失败时换下一个候选，最多尝试maxAttempts个
    public Mono<ChatCompletion> complete(ChatRequest request, String requestId) {
        return attempComplete(request, requestId, healthyCandidates(request.model()), 0);
    }

    private Mono<ChatCompletion> attempComplete(ChatRequest request, String requestId,
                                                List<ModelInstance> candidates, int attempt) {
        if (candidates.isEmpty()) {
            return Mono.error(new GatewayException(503, "no_available_model",
                    "模型[" + request.model() + "] 当前没有可用实例"));
        }
        ModelInstance chosen = new WeightedRandomPicker(candidates).pick()
                .orElseThrow(() -> new GatewayException(503, "no_available_model", "候选为空"));
        log(requestId, "路由选择", chosen.instanceId());

        return connector.complete(chosen, request)
                .doOnSuccess(r -> metrics.success(request.model(), chosen.instanceId()))
                .doOnError(e -> metrics.failure(request.model(), chosen.instanceId()))
                .onErrorResume(e -> {
                    log(requestId, "候选失败，尝试下一个", chosen.instanceId() + " - "
                            + e.getMessage());
                    // 用“剩余候选”重新做加权随机，保证失败切换后权重仍然生效
                    List<ModelInstance> rest = remaining(candidates, chosen);
                    if (attempt + 1 >= maxAttempts() || rest.isEmpty()) {
                        return Mono.error(new GatewayException(502, "upstream_failed",
                                "所有候选均失败：" + e.getMessage()));
                    }
                    return attempComplete(request, requestId, rest, attempt + 1);
                });
    }


    /** 流式：与上面同构，只是返回 Flux；首字节前失败才切换候选 */
    public Flux<ChatChunk> stream(ChatRequest request, String requestId) {
        return attemptStream(request, requestId, healthyCandidates(request.model()), 0);
    }

    private Flux<ChatChunk> attemptStream(ChatRequest request, String requestId,
                                          List<ModelInstance> candidates, int attempt) {
        if (candidates.isEmpty()) {
            return Flux.error(new GatewayException(503, "no_available_model",
                    "模型[" + request.model() + "] 当前没有可用实例"));
        }
        ModelInstance chosen = new WeightedRandomPicker(candidates).pick()
                .orElseThrow(() -> new GatewayException(503, "no_available_model", "候选为空"));
        log(requestId, "路由选择(流式)", chosen.instanceId());

        // 只对“尚未开始输出”的错误做切换；已经开始输出则直接失败（见 V3 安全重试边界）
        return connector.stream(chosen, request)
                .doOnNext(chunk -> metrics.streamChunk(request.model(), chosen.instanceId()))
                .onErrorResume(e -> {
                    log(requestId, "流式候选失败(首字节前)，尝试下一个",
                            chosen.instanceId() + " - " + e.getMessage());
                    List<ModelInstance> rest = remaining(candidates, chosen);
                    if (attempt + 1 >= maxAttempts() || rest.isEmpty()) {
                        return Flux.error(new GatewayException(502, "upstream_failed",
                                "所有候选均失败: " + e.getMessage()));
                    }
                    return attemptStream(request, requestId, rest, attempt + 1);
                });
    }

    private List<ModelInstance> healthyCandidates(String alias) {
        return registry.findByAlias(alias).stream()
                .filter(inst -> healthChecker.isHealthy(inst.channelId()))
                .toList();
    }

    /** 排除已失败的候选，剩余候选下次重新加权随机 */
    private List<ModelInstance> remaining(List<ModelInstance> candidates, ModelInstance chosen) {
        return candidates.stream()
                .filter(c -> !c.instanceId().equals(chosen.instanceId()))
                .toList();
    }

    private int maxAttempts() {
        return 2; // 可配置化（V5 热更新），V1 先用常量
    }

    /** 日志约定（详见实施计划第 12.1 节）：[requestId=xxx] event=xxx detail=xxx */
    private void log(String requestId, String event, String detail) {
        LOG.info("[requestId={}] event={} detail={}", requestId, event, detail);
    }

}
