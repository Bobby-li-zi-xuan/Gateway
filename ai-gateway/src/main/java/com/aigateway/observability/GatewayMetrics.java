package com.aigateway.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 基础指标（V1）：请求成功/失败计数、流式 chunk 计数。
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

    /** 构建（或复用）一个带固定标签的计数器；重复调用 register 会自动复用同名指标 */
    private Counter counter(String name, String alias, String instance, String result) {
        return Counter.builder(name)
                .tag("model", alias)
                .tag("instance", instance)
                .tag("result", result)
                .register(meterRegistry);
    }
}
