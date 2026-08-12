package com.aigateway.execution.stream;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.api.dto.ChatCompletion;

/**
 * 计量回调（脚手架，代码段 S11b）：
 * V4 计量接入点；本版本默认实现只打日志，V4 替换为 Token 累加实现。
 * ⚠️ StreamProxy 构造器注入本接口，容器中必须存在实现 Bean（见 GatewayBeans 缺省实现）。
 */
public interface MeteringCallback {

    /** 每个 chunk 触发（V4 接入 Token 累加；失败不影响转发） */
    default void onChunk(ChatChunk chunk) {}

    /** 流结束触发（V4 用近似公式替换 usageOrApproximate） */
    default void onFinish(ChatCompletion.Usage usage) {}
}
