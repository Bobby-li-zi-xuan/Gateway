package com.aigateway.execution.fallback;

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
import com.aigateway.execution.model.UpstreamCallException;
import com.aigateway.execution.policy.ExecutionPolicyManager;
import com.aigateway.execution.retry.RetryExecutor;
import com.aigateway.execution.timeout.TimeoutGuard;
import com.aigateway.observability.GatewayMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Time;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 降级链执行器（非流式）：按序尝试候选，每个候选内部由 RetryExecutor 处理同实例重试。
 *
 * 要点（对照计划 12.2）：
 * 1. 每个候选尝试前：查总时长（guard.expired）→ 查熔断 → 查冷却，任一命中就跳过（记录原因）；
 * 2. 候选调用失败：可重试的交给 RetryExecutor（连接重试 / 429 Retry-After），其余直接换下一个；
 * 3. 全部候选失败：区分"尝试过但失败"（502/504）与"全被熔断/冷却跳过"（503 circuit_open/cooldown_active）；
 * 4. 记账收口：熔断窗口、冷却计数、状态事件统一在 recordOutcome 一处完成（风险第 5 条）；
 * 5. 总时长是硬边界：预算用完立即放弃，不再发起新尝试。
 */
@Component
public class FallbackChainExecutor {

    private static final Logger log = LoggerFactory.getLogger(FallbackChainExecutor.class);

    private final OpenAIConnector connector;
    private final CircuitBreakerRegistry circuitBreakers;
    private final CooldownManager cooldowns;
    private final RetryExecutor retryExecutor;
    private final ExecutionPolicyManager policyManager;
    private final ModelStateStore stateStore;
    private final StateEventPipeline stateEvents;
    private final GatewayMetrics metrics;
    private final ScheduledExecutorService scheduler;

    public FallbackChainExecutor(OpenAIConnector connector,
                                 CircuitBreakerRegistry circuitBreakers,
                                 CooldownManager cooldowns,
                                 RetryExecutor retryExecutor,
                                 ExecutionPolicyManager policyManager,
                                 ModelStateStore stateStore,
                                 StateEventPipeline stateEvents,
                                 GatewayMetrics metrics,
                                 ScheduledExecutorService scheduler) {
        this.connector = connector;
        this.circuitBreakers = circuitBreakers;
        this.cooldowns = cooldowns;
        this.retryExecutor = retryExecutor;
        this.policyManager = policyManager;
        this.stateStore = stateStore;
        this.stateEvents = stateEvents;
        this.metrics = metrics;
        this.scheduler = scheduler;
    }

    /** 执行完整降级链；成功返回，失败抛 GatewayException */
    public ChatCompletion execute(List<ModelInstance> chain, ChatRequest request,
                                  String requestId, Map<String, String> metadata) {
        if (chain.isEmpty()) {
            throw new GatewayException(503, "no_available_model",
                    "模型[" + request.model() + "] 当前没有可用实例");
        }

        // 总时长预算取首选候选的请求级策略
        ExecutionPolicies firstPolicies = policyManager.forRequest(chain.get(0).channelId(), metadata);
        TimeoutGuard guard  = new TimeoutGuard(firstPolicies.totalMs(), scheduler);

        Failure lastFailure = null;
        boolean attempted = false;
        boolean skippedByCircuit = false;
        boolean skippedByCooldown = false;

        for(ModelInstance candidate : chain){
            if(guard.expired()){
                metrics.timeout(candidate.instanceId(), "total");
                throw mapFinalFailure(lastFailure, guard);
            }

            ExecutionPolicies policies = policyManager.forRequest(candidate.channelId(), metadata);

            // 熔断/冷却任一命中即剔除
            if(!circuitBreakers.allowRequest(candidate.instanceId())){
                skippedByCircuit = true;
                metrics.circuitRejected(candidate.instanceId());
                log(requestId, "候选被熔断跳过", candidate.describe());
                continue;
            }
            if (cooldowns.isCooling(candidate.channelId())) {
                skippedByCooldown = true;
                metrics.cooldownSkipped(candidate.channelId());
                log(requestId, "候选被冷却跳过", candidate.describe());
                continue;
            }

            attempted = true;
            long start = System.nanoTime();
            stateStore.beginRequest(candidate.instanceId());
            try {
                // RetryExecutor 只处理“同实例重试”；失败会以 UpstreamCallException 抛出
                ChatCompletion result = retryExecutor.execute(policies.retry(), guard,
                        candidate.instanceId(),
                        () -> connector.complete(candidate, request, policies));
                recordOutcome(candidate, true, elapsedMs(start), null, policies);
                metrics.success(request.model(), candidate.instanceId());
                log(requestId, "执行成功", candidate.describe());
                return result;
            } catch (UpstreamCallException e) {
                long elapsed = elapsedMs(start);
                lastFailure = e.failure();
                recordOutcome(candidate, false, elapsed, lastFailure, policies);
                metrics.failure(request.model(), candidate.instanceId());
                metrics.fallback(candidate.instanceId(), lastFailure.type().name());
                log(requestId, "候选失败，降级",
                        candidate.describe() + " reason=" + lastFailure.type());
            } finally {
                stateStore.endRequest(candidate.instanceId());
            }
        }

        if (!attempted) {
            // 全被跳过 ≠ 全失败：503 语义不同，错误体要能说清原因
            String type = skippedByCircuit ? "circuit_open" : "cooldown_active";
            throw new GatewayException(503, type,
                    "所有候选均被" + (skippedByCircuit ? "熔断" : "冷却") + "剔除，未发起上游调用");
        }
        throw mapFinalFailure(lastFailure, guard);      
    }

    /** 一次真实调用结束后：熔断窗口、状态管道、冷却计数全部在这里收口（单点记账） */
    private void recordOutcome(ModelInstance candidate, boolean success, long latencyMs,
                               Failure failure, ExecutionPolicies policies) {
        long slowThreshold = policies.circuitBreaker().slowCallThresholdMs();
        boolean slow = latencyMs >= slowThreshold;
        circuitBreakers.recordResult(candidate.instanceId(), success, latencyMs, slowThreshold);

        if (success) {
            stateEvents.offer(new StateEvent.Success(candidate.instanceId(), latencyMs));
            if (slow) {
                stateEvents.offer(new StateEvent.SlowCall(candidate.instanceId(), latencyMs));
            }
            cooldowns.recordSuccess(candidate.channelId());
        } else {
            stateEvents.offer(new StateEvent.Failure(candidate.instanceId(), failure.type()));
            if (slow) {
                stateEvents.offer(new StateEvent.SlowCall(candidate.instanceId(), latencyMs));
            }
            cooldowns.recordFailure(candidate.channelId());
        }
    }

     /** 最终失败 → 客户端错误体：超时映射 504，其余 502 */
    private GatewayException mapFinalFailure(Failure failure, TimeoutGuard guard) {
        if (guard.expired()) {
            return new GatewayException(504, "timeout_total", "整条降级链总时长超时");
        }
        if (failure == null) {
            return new GatewayException(502, "upstream_failed", "所有候选均失败");
        }
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
