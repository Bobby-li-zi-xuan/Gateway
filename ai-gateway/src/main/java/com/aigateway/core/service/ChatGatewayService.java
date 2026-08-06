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

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * 网关主流程：路由 + 转发 + 失败切换（阻塞式，运行在虚拟线程上）。
 *
 * 🖊 手敲 H2：本类全部手敲，按《版本1-详细实施计划》第 9 节实现。
 * 需要注入：ModelRegistry、HealthChecker、OpenAIConnector、GatewayMetrics。
 * 逻辑：findByAlias → 健康过滤（isHealthy）→ WeightedRandomPicker（H1）
 *       → connector 调用 → 失败换下一个候选（最多 maxAttempts 次，
 *         每次用排除失败候选之后的“剩余候选”重新做加权随机）。
 */
@Service
public class ChatGatewayService {

    private final ModelRegistry registry;
    private final HealthChecker healthChecker;
    private final OpenAIConnector connector;
    private final GatewayMetrics metrics;

    public ChatGatewayService(ModelRegistry registry,
                              HealthChecker healthChecker,
                              OpenAIConnector connector,
                              GatewayMetrics metrics) {
        this.registry = registry;
        this.healthChecker = healthChecker;
        this.connector = connector;
        this.metrics = metrics;
    }

    /** 非流式：失败时换下一个候选，最多尝试 maxAttempts 个（阻塞式） */
    public ChatCompletion complete(ChatRequest request, String requestId) {
        List<ModelInstance> candidates = healthyCandidates(request.model());
        if (candidates.isEmpty()) {
            throw new GatewayException(503, "no_available_model",
                    "模型[" + request.model() + "] 当前没有可用实例");
        }
        int attempt = 0;
        while (attempt < maxAttempts() && !candidates.isEmpty()) {
            ModelInstance chosen = pick(request, requestId, candidates);
            try {
                ChatCompletion completion = connector.complete(chosen, request);
                metrics.success(request.model(), chosen.instanceId());
                return completion;
            } catch (Exception e) {
                metrics.failure(request.model(), chosen.instanceId());
                log(requestId, "候选失败，尝试下一个", chosen.instanceId() + " - " + e.getMessage());
                candidates = remaining(candidates, chosen);
                attempt++;
            }
        }
        throw new GatewayException(502, "upstream_failed", "所有候选均失败");
    }

    private ModelInstance pick(ChatRequest request, String requestId, List<ModelInstance> candidates) {
        ModelInstance chosen = new WeightedRandomPicker(candidates).pick()
                .orElseThrow(() -> new GatewayException(503, "no_available_model", "候选为空"));
        log(requestId, "路由选择", chosen.instanceId());
        return chosen;
    }

    /** 流式：首字节前失败才切换候选；已经开始输出则直接中断（V3 安全重试边界） */
    public void stream(ChatRequest request, String requestId, Consumer<ChatChunk> consumer) {
        List<ModelInstance> candidates = healthyCandidates(request.model());
        if (candidates.isEmpty()) {
            throw new GatewayException(503, "no_available_model",
                    "模型[" + request.model() + "] 当前没有可用实例");
        }
        int attempt = 0;
        while (attempt < maxAttempts() && !candidates.isEmpty()) {
            ModelInstance chosen = pick(request, requestId, candidates);
            boolean[] started = {false};
            try {
                connector.stream(chosen, request, chunk -> {
                    metrics.streamChunk(request.model(), chosen.instanceId());
                    started[0] = true;
                    consumer.accept(chunk);
                });
                return;
            } catch (Exception e) {
                if (started[0]) {
                    throw new GatewayException(502, "upstream_failed",
                            "流式输出中断: " + e.getMessage(), e);
                }
                metrics.failure(request.model(), chosen.instanceId());
                log(requestId, "流式候选失败(首字节前)，尝试下一个",
                        chosen.instanceId() + " - " + e.getMessage());
                candidates = remaining(candidates, chosen);
                attempt++;
            }
        }
        throw new GatewayException(502, "upstream_failed", "所有候选均失败");
    }

    private List<ModelInstance> remaining(List<ModelInstance> candidates, ModelInstance chosen) {
        return candidates.stream()
                .filter(c -> !c.instanceId().equals(chosen.instanceId()))
                .toList();
    }

    private List<ModelInstance> healthyCandidates(String alias) {
        return registry.findByAlias(alias).stream()
                .filter(inst -> healthChecker.isHealthy(inst.channelId()))
                .toList();
    }

    private int maxAttempts() {
        return 2; // 可配置化（V5 热更新），V1 先用常量
    }

    /** 结构化日志：格式 [requestId=xxx] event=xxx detail=xxx（按计划文档第 9 节约定） */
    private static void log(String requestId, String event, String detail) {
        System.out.printf("[requestId=%s] event=%s detail=%s%n", requestId, event, detail);
    }
}
