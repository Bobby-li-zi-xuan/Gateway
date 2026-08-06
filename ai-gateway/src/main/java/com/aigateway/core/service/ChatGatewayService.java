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
 * 🖊 手敲 H2：本类为必手敲模块，按《版本1-详细实施计划》第 9 节实现。
 *
 * 一次请求的完整路由链路：
 *   findByAlias(别名) → 健康过滤（H4 isHealthy）→ 加权随机选候选（H1）
 *   → connector 调用上游 → 失败则排除该候选，用剩余候选重新加权随机
 *   → 最多尝试 maxAttempts 次 → 全部失败返回 502。
 *
 * 设计要点（学习）：
 * - 每次重试都“重新做加权随机”，而不是按固定顺序换下一个，
 *   保证失败切换后权重比例依然生效；
 * - 失败切换的粒度是候选实例；非流式可以直接换候选重试，
 *   流式只允许在“首字节前”切换（已经开始输出就不能安全重放，V3 再处理）。
 */
@Service
public class ChatGatewayService {

    private final ModelRegistry registry;      // 别名 → 候选实例列表
    private final HealthChecker healthChecker; // 渠道健康快照（H4 手敲）
    private final OpenAIConnector connector;   // 上游调用 + SSE 解析（H3 手敲）
    private final GatewayMetrics metrics;      // 成功/失败/chunk 指标

    /** Spring 构造器注入四个依赖（@Service 交给容器管理，依赖必须显式传入） */
    public ChatGatewayService(ModelRegistry registry,
                              HealthChecker healthChecker,
                              OpenAIConnector connector,
                              GatewayMetrics metrics) {
        this.registry = registry;
        this.healthChecker = healthChecker;
        this.connector = connector;
        this.metrics = metrics;
    }

    /**
     * 非流式：失败时换下一个候选，最多尝试 maxAttempts 个（阻塞式）。
     *
     * @return 第一个成功候选返回的完整 ChatCompletion
     * @throws GatewayException 无健康候选时 503 no_available_model；全部失败时 502 upstream_failed
     */
    public ChatCompletion complete(ChatRequest request, String requestId) {
        // 1. 取出该别名下的候选，并过滤掉健康检查不通过的渠道
        List<ModelInstance> candidates = healthyCandidates(request.model());
        if (candidates.isEmpty()) {
            throw new GatewayException(503, "no_available_model",
                    "模型[" + request.model() + "] 当前没有可用实例");
        }
        int attempt = 0;
        // 2. 循环尝试：候选没耗尽且还没达到最大尝试次数就继续
        while (attempt < maxAttempts() && !candidates.isEmpty()) {
            ModelInstance chosen = pick(request, requestId, candidates);
            try {
                // 3. 调用上游：成功则计数并返回
                ChatCompletion completion = connector.complete(chosen, request);
                metrics.success(request.model(), chosen.instanceId());
                return completion;
            } catch (Exception e) {
                // 4. 失败：计数、打日志、排除该候选后进入下一轮（重新加权随机）
                metrics.failure(request.model(), chosen.instanceId());
                log(requestId, "候选失败，尝试下一个", chosen.instanceId() + " - " + e.getMessage());
                candidates = remaining(candidates, chosen);
                attempt++;
            }
        }
        // 5. 所有候选都失败（或达到尝试上限）→ 502
        throw new GatewayException(502, "upstream_failed", "所有候选均失败");
    }

    /** 对当前候选列表做一次加权随机选择，并记录路由日志 */
    private ModelInstance pick(ChatRequest request, String requestId, List<ModelInstance> candidates) {
        ModelInstance chosen = new WeightedRandomPicker(candidates).pick()
                .orElseThrow(() -> new GatewayException(503, "no_available_model", "候选为空"));
        log(requestId, "路由选择", chosen.instanceId());
        return chosen;
    }

    /**
     * 流式：首字节前失败才切换候选；已经开始输出则直接中断（V3 安全重试边界）。
     *
     * 与 complete() 的区别：
     * - 通过 consumer 回调把每个 chunk 转给上层（Controller 写入 SseEmitter）；
     * - started 标记“是否已输出过 chunk”：一旦输出过就说明连接已建立、内容已下发，
     *   此时上游断开不能盲目重试（客户端会收到重复内容），直接抛错让连接以错误结束。
     */
    public void stream(ChatRequest request, String requestId, Consumer<ChatChunk> consumer) {
        List<ModelInstance> candidates = healthyCandidates(request.model());
        if (candidates.isEmpty()) {
            throw new GatewayException(503, "no_available_model",
                    "模型[" + request.model() + "] 当前没有可用实例");
        }
        int attempt = 0;
        while (attempt < maxAttempts() && !candidates.isEmpty()) {
            ModelInstance chosen = pick(request, requestId, candidates);
            // started 用单元素数组：lambda 里要修改外部变量，Java 要求它“有效最终”，
            // 数组元素不是变量本身，所以可以修改（学习点：lambda 捕获）
            boolean[] started = {false};
            try {
                connector.stream(chosen, request, chunk -> {
                    metrics.streamChunk(request.model(), chosen.instanceId());
                    started[0] = true; // 只要有 chunk 到达，就标记“已开始输出”
                    consumer.accept(chunk);
                });
                return;
            } catch (Exception e) {
                if (started[0]) {
                    // 已经开始输出后失败：不能安全重试，直接中断
                    throw new GatewayException(502, "upstream_failed",
                            "流式输出中断: " + e.getMessage(), e);
                }
                // 首字节前失败：与 complete() 一样排除候选后重试
                metrics.failure(request.model(), chosen.instanceId());
                log(requestId, "流式候选失败(首字节前)，尝试下一个",
                        chosen.instanceId() + " - " + e.getMessage());
                candidates = remaining(candidates, chosen);
                attempt++;
            }
        }
        throw new GatewayException(502, "upstream_failed", "所有候选均失败");
    }

    /** 排除已失败候选后的剩余列表（不可变流式操作，返回新列表） */
    private List<ModelInstance> remaining(List<ModelInstance> candidates, ModelInstance chosen) {
        return candidates.stream()
                .filter(c -> !c.instanceId().equals(chosen.instanceId()))
                .toList();
    }

    /** 别名 → 候选实例，再按渠道健康状态过滤（isHealthy 是纯内存读，零阻塞） */
    private List<ModelInstance> healthyCandidates(String alias) {
        return registry.findByAlias(alias).stream()
                .filter(inst -> healthChecker.isHealthy(inst.channelId()))
                .toList();
    }

    /** 最大尝试次数：V1 先写死为 2（首次 + 一次切换），V5 再做成可配置 */
    private int maxAttempts() {
        return 2; // 可配置化（V5 热更新），V1 先用常量
    }

    /**
     * 结构化日志：格式 [requestId=xxx] event=xxx detail=xxx（按计划文档第 9 节约定）。
     * 用 System.out 便于学习阶段直接观察；生产可换 slf4j Logger。
     */
    private static void log(String requestId, String event, String detail) {
        System.out.printf("[requestId=%s] event=%s detail=%s%n", requestId, event, detail);
    }
}
