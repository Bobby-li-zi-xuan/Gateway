package com.aigateway.execution.circuit;

import com.aigateway.decision.state.StateEvent;
import com.aigateway.decision.state.StateEventPipeline;
import com.aigateway.execution.model.CircuitBreakerConfig;
import com.aigateway.execution.policy.ExecutionPolicyManager;
import com.aigateway.observability.GatewayMetrics;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断注册表：
 * 按 instanceId 懒加载 {@link CircuitBreaker} 实例（熔断粒度 = 渠道 + 模型）。
 *
 * 职责（仅委托，不含状态机逻辑）：
 * - allowRequest / recordResult 转发给对应实例；
 * - recordResult 后对比状态转换，发布 {@link StateEvent.CircuitTransition} 到状态管道
 *   （决策层可见），并记录 gateway.circuit.breaker.events 指标。
 *
 */
@Component
public class CircuitBreakerRegistry {

    private final CircuitBreakerConfig config;
    private final StateEventPipeline stateEvents;
    private final GatewayMetrics metrics;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreakerRegistry(ExecutionPolicyManager policyManager,
                                  StateEventPipeline stateEvents, GatewayMetrics metrics) {
        this.config = policyManager.defaults().circuitBreaker();
        this.stateEvents = stateEvents;
        this.metrics = metrics;
    }

    /** 是否放行本次请求（执行前调用，转发给实例） */
    public boolean allowRequest(String instanceId) {
        return breaker(instanceId).allowRequest();
    }

    /** 记录一次真实调用结果，并把状态转换发布为事件 + 指标 */
    public void recordResult(String instanceId, boolean success, long latencyMs, long slowThresholdMs) {
        CircuitBreaker breaker = breaker(instanceId);
        // 转换在 record 锁内判定并返回：锁外比较状态会让并发请求重复发布同一转换
        CircuitBreaker.StateTransition t = breaker.record(success, latencyMs, slowThresholdMs);
        if (t.from() != t.to()) {
            stateEvents.offer(new StateEvent.CircuitTransition(instanceId, t.from(), t.to()));
            metrics.circuitEvent(instanceId, t.to().name().toLowerCase());
        }
    }

    /** 懒加载实例（同一 instanceId 全局唯一） */
    private CircuitBreaker breaker(String instanceId) {
        return breakers.computeIfAbsent(instanceId, id -> new CircuitBreaker(id, config));
    }
}
