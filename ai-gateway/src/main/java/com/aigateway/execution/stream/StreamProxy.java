package com.aigateway.execution.stream;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.api.dto.ChatCompletion;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.decision.state.ModelStateStore;
import com.aigateway.decision.state.StateEvent;
import com.aigateway.decision.state.StateEventPipeline;
import com.aigateway.execution.circuit.CircuitBreakerRegistry;
import com.aigateway.execution.connector.OpenAIConnector;
import com.aigateway.execution.cooldown.CooldownManager;
import com.aigateway.execution.model.ExecutionPolicies;
import com.aigateway.execution.model.Failure;
import com.aigateway.execution.model.StreamSession;
import com.aigateway.execution.model.UpstreamCallException;
import com.aigateway.execution.policy.ExecutionPolicyManager;
import com.aigateway.execution.timeout.TimeoutGuard;
import com.aigateway.observability.GatewayMetrics;

import io.micrometer.core.instrument.TimeGauge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * ⚠️ 手敲 H6（详细实施计划第 13 节 S11）——本类全部逻辑需手敲，方法体当前抛 TODO 异常。
 *
 * 流式代理：在连接器之上做"治理"，连接器只负责低层 SSE 读取与超时关闭。
 *
 * 手敲要点（对照计划 13.2，本版本最难也最值钱的部分）：
 * 1. 逐 chunk 规范化（补齐 id/created/model，错误事件转统一错误体）；
 * 2. 首字节标记：首字节前失败可降级，首字节后失败只能中断（安全边界，不重放）；
 * 3. 客户端断开（ClientDisconnectedException）→ 连接器取消上游，直接返回（不换候选、不发错误）；
 * 4. 每 chunk 触发计量回调（失败不影响转发）；流结束触发 onFinish；
 * 5. 记账收口与非流式一致（recordOutcome 一处完成）；流式语义同 H5。
 */
@Component
public class StreamProxy {

    private static final Logger log = LoggerFactory.getLogger(StreamProxy.class);

    /** 一次流式候选尝试的结果 */
    private enum OutcomeKind { SUCCESS, CANCELLED, FAILED }
    private record StreamOutcome(OutcomeKind kind, Failure failure) {
        static StreamOutcome success() { return new StreamOutcome(OutcomeKind.SUCCESS, null); }
        static StreamOutcome cancelled() { return new StreamOutcome(OutcomeKind.CANCELLED, null); }
        static StreamOutcome failed(Failure f) { return new StreamOutcome(OutcomeKind.FAILED, f); }
    }

    private final OpenAIConnector connector;
    private final CircuitBreakerRegistry circuitBreakers;
    private final CooldownManager cooldowns;
    private final ExecutionPolicyManager policyManager;
    private final ModelStateStore stateStore;
    private final StateEventPipeline stateEvents;
    private final GatewayMetrics metrics;
    private final MeteringCallback metering;
    private final ScheduledExecutorService scheduler;

    public StreamProxy(OpenAIConnector connector,
                       CircuitBreakerRegistry circuitBreakers,
                       CooldownManager cooldowns,
                       ExecutionPolicyManager policyManager,
                       ModelStateStore stateStore,
                       StateEventPipeline stateEvents,
                       GatewayMetrics metrics,
                       MeteringCallback metering,
                       ScheduledExecutorService scheduler) {
        this.connector = connector;
        this.circuitBreakers = circuitBreakers;
        this.cooldowns = cooldowns;
        this.policyManager = policyManager;
        this.stateStore = stateStore;
        this.stateEvents = stateEvents;
        this.metrics = metrics;
        this.metering = metering;
        this.scheduler = scheduler;
    }

    /** 流式降级链：语义与非流式一致，多一条"首字节后不降级"的硬边界 */
    public void streamChain(List<ModelInstance> chain, ChatRequest request, String requestId,
                            Map<String, String> metadata, Consumer<ChatChunk> consumer) {
        if(chain.isEmpty()){
            throw new GatewayException(503, "no_available_model", "模型[" + request.model() + "] 当前没有可用实例");
        }
        ExecutionPolicies firstPolicies = policyManager.forRequest(chain.get(0).channelId(), metadata);
        TimeoutGuard guard = new TimeoutGuard(firstPolicies.totalMs(), scheduler);

        Failure lastFailure = null;
        boolean attempted = false;
        boolean skippedByCircuit = false;
        boolean skippedByCooldown = false;

        for (ModelInstance candidate : chain) {
            if (guard.expired()) {
                metrics.timeout(candidate.instanceId(), "total");
                throw mapFinalFailure(lastFailure, guard);
            }
            ExecutionPolicies policies = policyManager.forRequest(candidate.channelId(), metadata);

            if (!circuitBreakers.allowRequest(candidate.instanceId())) {
                skippedByCircuit = true;
                metrics.circuitRejected(candidate.instanceId());
                log(requestId, "流式候选被熔断跳过", candidate.describe());
                continue;
            }
            if (cooldowns.isCooling(candidate.channelId())) {
                skippedByCooldown = true;
                metrics.cooldownSkipped(candidate.channelId());
                log(requestId, "流式候选被冷却跳过", candidate.describe());
                continue;
            }
    
            attempted = true;
            long start = System.nanoTime();
            boolean[] started = {false};   // 首字节标记：lambda 内修改外部变量用数组（学习点）
            stateStore.beginRequest(candidate.instanceId());
            try {
                StreamOutcome outcome = streamOnce(candidate, request, policies,
                        requestId, started, consumer);
                if (outcome.kind() == OutcomeKind.CANCELLED) {
                    // 客户端已断开：取消已完成，直接结束本次请求（不换候选、不发错误）
                    metrics.streamCancelled(request.model(), candidate.instanceId());
                    log(requestId, "客户端断开，已取消上游", candidate.describe());
                    return;
                }
                if (outcome.kind() == OutcomeKind.SUCCESS) {
                    recordOutcome(candidate, true, elapsedMs(start), null, policies);
                    metrics.success(request.model(), candidate.instanceId());
                    metering.onFinish(usageOrApproximate(candidate, request));
                    return;
                }

                // 失败：
                lastFailure = outcome.failure();
                recordOutcome(candidate, false, elapsedMs(start), lastFailure, policies);
                metrics.failure(request.model(), candidate.instanceId());
                if (started[0]) {
                    // ⚠️ 安全边界：首字节后绝不重试/降级（客户端已收到部分内容）
                    throw new GatewayException(502, "stream_interrupted",
                            "流式输出中断: " + lastFailure.reason());
                }
                metrics.fallback(candidate.instanceId(), lastFailure.type().name());
                log(requestId, "流式候选失败(首字节前)，降级",
                        candidate.describe() + " reason=" + lastFailure.type());
            } finally {
                stateStore.endRequest(candidate.instanceId());
            }
        }

        if (!attempted) {
            String type = skippedByCircuit ? "circuit_open" : "cooldown_active";
            throw new GatewayException(503, type,
                    "所有候选均被" + (skippedByCircuit ? "熔断" : "冷却") + "剔除，未发起上游调用");
        }
        throw mapFinalFailure(lastFailure, guard);
    }

    /** 单个候选的流式调用：连接器负责超时/取消，本层负责规范化与断连识别 */
    private StreamOutcome streamOnce(ModelInstance candidate, ChatRequest request,
                                     ExecutionPolicies policies, String requestId,
                                     boolean[] started, Consumer<ChatChunk> consumer) {
         StreamState state = new StreamState(candidate.model());
        try (StreamSession session = connector.stream(candidate, request, policies, chunk -> {
            ChatChunk normalized = normalize(chunk, state);
            if (!started[0]) started[0] = true;
            try {
                metering.onChunk(normalized);      // 计量回调：失败不影响转发
            } catch (Exception e) {
                // 计量异常必须吞掉：计量是附加能力，不能反过来打断转发
                log.warn("[requestId={}] 计量回调异常（忽略）: {}", requestId, e.toString());
            }
            consumer.accept(normalized);           // 客户端断开时抛 ClientDisconnectedException
        })) {
            return StreamOutcome.success();
        } catch (ClientDisconnectedException e) {
            return StreamOutcome.cancelled();
        } catch (UpstreamCallException e) {
            return StreamOutcome.failed(e.failure());
        }
    }

    /**
     * SSE 规范化：补齐上游缺失的 id / created / model，错误事件透传为统一错误体。
     * 规则：首个非空值记入 StreamState，后续 chunk 复用同一个 id（整个流共享响应 ID）。
     */
    private ChatChunk normalize(ChatChunk raw, StreamState state) {
        if (raw.id() != null) state.id(raw.id());
        if (raw.created() != 0) state.created(raw.created());
        if (raw.model() != null) state.model(raw.model());
        if (raw.usage() != null) state.usage(raw.usage());
        return new ChatChunk(
                raw.id() != null ? raw.id() : state.id(),
                "chat.completion.chunk",                    // object 固定规范化
                raw.created() != 0 ? raw.created() : state.created(),
                raw.model() != null ? raw.model() : state.model(),
                raw.choices(),
                raw.usage() != null ? raw.usage() : null,
                raw.error());
    }

    private void recordOutcome(ModelInstance candidate, boolean success, long latencyMs,
                               Failure failure, ExecutionPolicies policies) {
        long slowThreshold = policies.circuitBreaker().slowCallThresholdMs();
        boolean slow = latencyMs >= slowThreshold;
        circuitBreakers.recordResult(candidate.instanceId(), success, latencyMs, slowThreshold);
        if (success) {
            stateEvents.offer(new StateEvent.Success(candidate.instanceId(), latencyMs));
            cooldowns.recordSuccess(candidate.channelId());
        } else {
            stateEvents.offer(new StateEvent.Failure(candidate.instanceId(), failure.type()));
            cooldowns.recordFailure(candidate.channelId());
        }
        if (slow) {
            stateEvents.offer(new StateEvent.SlowCall(candidate.instanceId(), latencyMs));
        }
    }

    /** usage 缺失时的近似值（V4 细化真实计量） */
    private ChatCompletion.Usage usageOrApproximate(ModelInstance candidate, ChatRequest request) {
        return new ChatCompletion.Usage(0, 0, 0); // V4 用估算公式替换
    }

    private GatewayException mapFinalFailure(Failure failure, TimeoutGuard guard) {
        if (guard.expired()) {
            return new GatewayException(504, "timeout_total", "整条降级链总时长超时");
        }
        if (failure == null) {
            return new GatewayException(502, "upstream_failed", "所有候选均失败");
        }
        // 与非流式（12.2）同一映射，保证 15.2 错误码表在流式场景下同样可达
        // （TIMEOUT_IDLE 发生在首字节后、已走 stream_interrupted，此分支实际不可达，保留对称）
        return switch (failure.type()) {
            case TIMEOUT_CONNECT -> new GatewayException(504, "timeout_connect", failure.reason());
            case TIMEOUT_REQUEST -> new GatewayException(504, "timeout_request", failure.reason());
            case TIMEOUT_FIRST_BYTE -> new GatewayException(504, "timeout_first_byte", failure.reason());
            case TIMEOUT_IDLE -> new GatewayException(504, "timeout_idle", failure.reason());
            case TIMEOUT_TOTAL -> new GatewayException(504, "timeout_total", failure.reason());
            default -> new GatewayException(502, "upstream_failed",
                    "所有候选均失败: " + failure.type() + " - " + failure.reason());
        };
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static void log(String requestId, String event, String detail) {
        // 结构化日志（SLF4J）：可按级别过滤、可携带 MDC 上下文（requestId）
        log.info("[requestId={}] event={} detail={}", requestId, event, detail);
    }
}