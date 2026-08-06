package com.aigateway.core.domain.model;

/**
 * 模型候选实例：一个“别名 → 渠道 + 上游模型”的映射。
 *
 * 例如：alias=qwen 的候选可以是 channelId=mock-a + model=qwen-large。
 * 路由时先按别名取出候选列表，再结合健康状态做加权随机选择。
 *
 * 学习要点：
 * - instanceId 由 channelId:model 拼成，保证同一渠道下不同模型互不冲突；
 * - weight 是“实例级”权重，与 Channel.weight 含义不同：前者决定同一别名下
 *   候选之间的比例，后者在后续版本中用于跨别名的渠道调度；
 * - V2 新增价格/质量/延迟画像：Scorer 用它们计算 cost / quality / latency 因子，
 *   配置缺省时由 ModelRegistry 填默认值（qualityScore 从 qualityLevel 推导）。
 */
public record ModelInstance(
        String instanceId,      // 唯一实例 ID（channelId + ":" + model）
        String alias,           // 对外暴露的模型名（客户端传入的名称）
        String channelId,       // 所属渠道
        String model,           // 上游真实模型名
        int weight,             // 候选权重（> 0，启动校验保证）
        Capability capability,  // 模型能力画像（V2 调度用）
        double priceIn,         // V2：每 1K input tokens 价格（成本因子）
        double priceOut,        // V2：每 1K output tokens 价格
        double qualityScore,    // V2：0~1 静态质量分（质量因子）
        long latencyProfileMs   // V2：EWMA 延迟初始值
) {

    /** 日志友好描述：qwen -> mock-a/qwen-large */
    public String describe() {
        return alias + " -> " + channelId + "/" + model;
    }
}
