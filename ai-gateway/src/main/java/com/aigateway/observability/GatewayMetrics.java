package com.aigateway.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 基础指标（V1）：请求成功/失败计数、流式 chunk 计数。
 * 通过 /actuator/prometheus 暴露。
 */
@Component
public class GatewayMetrics {

    private final MeterRegistry meterRegistry;

    public GatewayMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void success(String alias, String instanceId) {
        counter("gateway.requests.total", alias, instanceId, "success").increment();
    }

    public void failure(String alias, String instanceId) {
        counter("gateway.requests.total", alias, instanceId, "failure").increment();
    }

    public void streamChunk(String alias, String instanceId) {
        counter("gateway.stream.chunks", alias, instanceId, "").increment();
    }

    private Counter counter(String name, String alias, String instance, String result) {
        return Counter.builder(name)
                .tag("model", alias)
                .tag("instance", instance)
                .tag("result", result)
                .register(meterRegistry);
    }
}
