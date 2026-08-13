package com.aigateway.execution.stream;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.api.dto.ChatCompletion;
import com.aigateway.governance.model.MeteredUsage;

/**
 * 计量回调（V4 签名，对照《版本4-详细实施计划》14.3）：
 * StreamProxy 逐 chunk 触发 onChunk 增量累计，流结束触发 onFinish 结算。
 * TokenMeter（H4）实现本接口；未实现计量时默认返回 null，GovernanceService 按估算兜底。
 */
public interface MeteringCallback {

    /** 每个 chunk 触发（V4 接入 Token 累加；失败不影响转发） */
    default void onChunk(MeteringContext ctx, ChatChunk chunk) {}

    /**
     * 流结束结算：返回 MeteredUsage（StreamProxy 忽略返回值；
     * TokenMeter 会把**成功**请求的结果写入内部 finished 表，
     * 供 GovernanceService.takeResult 取走——失败请求不写，避免残留；
     * 未实现计量时默认返回 null，GovernanceService 按估算兜底）。
     */
    default MeteredUsage onFinish(MeteringContext ctx, ChatCompletion.Usage usage, boolean success) {
        return null;
    }
}
