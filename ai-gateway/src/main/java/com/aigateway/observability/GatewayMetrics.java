package com.aigateway.observability;

import com.aigateway.decision.model.RoutingDecision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基础指标：请求成功/失败计数、流式 chunk 计数（V1）；
 * 决策分布与过滤淘汰计数（V2）。
 * 通过 /actuator/prometheus 暴露（Prometheus 文本格式）。
 *
 * 学习要点：
 * - Micrometer 的 Counter 是“只增不减”的计数器；
 * - 标签（tag）用于维度聚合：同一个指标名 + 不同标签值（model / instance / result）
 *   会生成多组时间序列，方便按维度查询；
 * - Prometheus 指标名 gateway.requests.total 会被自动转成 gateway_requests_total。
 */
@Component
public class GatewayMetrics {

    private final MeterRegistry meterRegistry;
    /** filterDrops 的 Counter 缓存：避免每次调用重建 Builder（Micrometer 会复用同名 meter，缓存仅省去重复构建开销） */
    private final Map<String, Counter> filterDropCounters = new ConcurrentHashMap<>();

    public GatewayMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** 记录一次成功请求（result=success） */
    public void success(String alias, String instanceId) {
        counter("gateway.requests.total", alias, instanceId, "success").increment();
    }

    /** 记录一次失败请求（result=failure） */
    public void failure(String alias, String instanceId) {
        counter("gateway.requests.total", alias, instanceId, "failure").increment();
    }

    /** 流式模式下每转发一个 chunk 计数一次，可观察生成速率 */
    public void streamChunk(String alias, String instanceId) {
        counter("gateway.stream.chunks", alias, instanceId, "").increment();
    }

    /** V2：决策分布 gateway_decisions_total{model,strategy}（对应设计文档 11.1） */
    public void decision(RoutingDecision decision) {
        counter("gateway.decisions.total", decision.alias(), decision.strategy(), "")
                .increment();
    }

    /** V2：过滤淘汰计数 gateway_filter_drops_total{model,filter} */
    public void filterDrops(String alias, String filter, int dropped) {
        filterDropCounters.computeIfAbsent(alias + "|" + filter, k ->
                        Counter.builder("gateway.filter.drops")
                                .tag("model", alias)
                                .tag("filter", filter)
                                .register(meterRegistry))
                .increment(dropped);
    }

    // ── V3 增量：容错事件指标（详细实施计划 15.1）──

    /** 重试事件：gateway_retry_total{instance,reason}（设计文档 7.2 的 retry_total{from,to,reason}） */
    public void retry(String instanceId, String reason) {
        Counter.builder("gateway.retry.total")
                .tag("instance", instanceId)
                .tag("reason", reason)
                .register(meterRegistry).increment();
    }

    /** 降级事件：gateway_fallback_events{from,reason}（换候选时记录） */
    public void fallback(String fromInstance, String reason) {
        Counter.builder("gateway.fallback.events")
                .tag("from", fromInstance)
                .tag("reason", reason)
                .register(meterRegistry).increment();
    }

    /** 超时事件：gateway_timeout_total{instance,stage}（stage=connect/request/first_byte/idle/total） */
    public void timeout(String instanceId, String stage) {
        Counter.builder("gateway.timeout.total")
                .tag("instance", instanceId)
                .tag("stage", stage)
                .register(meterRegistry).increment();
    }

    /** 熔断转换：gateway_circuit_breaker_events{instance,to}（to=open/half_open/close） */
    public void circuitEvent(String instanceId, String to) {
        Counter.builder("gateway.circuit.breaker.events")
                .tag("instance", instanceId)
                .tag("to", to)
                .register(meterRegistry).increment();
    }

    /** 熔断拒绝：gateway_circuit_breaker_rejections{instance}（快速失败次数） */
    public void circuitRejected(String instanceId) {
        Counter.builder("gateway.circuit.breaker.rejections")
                .tag("instance", instanceId)
                .register(meterRegistry).increment();
    }

    /** 冷却事件：gateway_cooldown_events{channel,to}（to=enter/exit） */
    public void cooldownEvent(String channelId, String to) {
        Counter.builder("gateway.cooldown.events")
                .tag("channel", channelId)
                .tag("to", to)
                .register(meterRegistry).increment();
    }

    /** 冷却跳过：gateway_cooldown_skipped_total{channel}（候选被冷却剔除次数） */
    public void cooldownSkipped(String channelId) {
        Counter.builder("gateway.cooldown.skipped_total")
                .tag("channel", channelId)
                .register(meterRegistry).increment();
    }

    /** 流式取消：gateway_stream_cancelled{model,instance}（客户端断开，不算失败） */
    public void streamCancelled(String alias, String instanceId) {
        Counter.builder("gateway.stream.cancelled")
                .tag("model", alias)
                .tag("instance", instanceId)
                .register(meterRegistry).increment();
    }

    /** 构建（或复用）一个带固定标签的计数器；重复调用 register 会自动复用同名指标 */
    private Counter counter(String name, String alias, String instance, String result) {
        return Counter.builder(name)
                .tag("model", alias)
                .tag("instance", instance)
                .tag("result", result)
                .register(meterRegistry);
    }
}
