package com.aigateway.execution.stream;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;

/**
 * 计量上下文：一次流式请求的身份 + 请求 + 实例（StreamProxy 构造后传给回调）。
 * instance 是**实际执行候选**（不是首个候选）——预算扣减必须按实际走了谁扣。
 */
public record MeteringContext(String requestId, ModelInstance instance,
                              ChatRequest request, String tokenId) {}
