package com.aigateway.core.service;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.api.dto.ChatCompletion;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.execution.fallback.ExecutionChainResolver;
import com.aigateway.execution.fallback.FallbackChainExecutor;
import com.aigateway.execution.stream.StreamProxy;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 网关主服务（V3 接线版）：只做"解析候选链 + 交给执行器"，容错细节全部下沉（12.3）。
 *
 * 分工：
 * - 决策（去哪家）：ExecutionChainResolver 调 DecisionEngine 产出主选 + 降级链；
 * - 执行（怎么调）：非流式交给 FallbackChainExecutor（H5）、流式交给 StreamProxy（H6），
 *   重试 / 分层超时 / 熔断 / 冷却全部在 execution 包内完成，本类不再手写循环。
 *
 * ⚠️ V3 执行层不再运行插件的执行阶段钩子（BEFORE_EXECUTION / AFTER_EXECUTION / ON_ERROR）；
 * 插件在决策阶段（BEFORE_DECISION / AFTER_DECISION）仍生效。H5 / H6 未手敲前，
 * 调用对应功能会直接抛 TODO 异常（计划预期的"未完成前直接报错"）。
 */
@Service
public class ChatGatewayService {

    private final ExecutionChainResolver chainResolver;
    private final FallbackChainExecutor fallbackExecutor;
    private final StreamProxy streamProxy;

    public ChatGatewayService(ExecutionChainResolver chainResolver,
                              FallbackChainExecutor fallbackExecutor,
                              StreamProxy streamProxy) {
        this.chainResolver = chainResolver;
        this.fallbackExecutor = fallbackExecutor;
        this.streamProxy = streamProxy;
    }

    /** 非流式入口：解析候选链 → 降级链执行器（重试/超时/熔断/冷却/降级在内部） */
    public ChatCompletion complete(ChatRequest request, String requestId,
                                   Map<String, String> metadata) {
        return fallbackExecutor.execute(
                chainResolver.resolve(request, requestId, metadata),
                request, requestId, metadata);
    }

    /** 流式入口：解析候选链 → 流式代理（SSE 规范化 / 首字节边界 / 取消上游） */
    public void stream(ChatRequest request, String requestId, Map<String, String> metadata,
                       Consumer<ChatChunk> consumer) {
        streamProxy.streamChain(
                chainResolver.resolve(request, requestId, metadata),
                request, requestId, metadata, consumer);
    }
}
