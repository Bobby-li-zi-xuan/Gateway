package com.aigateway.core.domain.model;

/**
 * 模型候选实例：一个“别名 → 渠道 + 上游模型”的映射。
 */
public record ModelInstance(
        String instanceId,
        String alias,
        String channelId,
        String model,
        int weight,
        Capability capability
) {
    public String describe() {
        return alias + " -> " + channelId + "/" + model;
    }
}
