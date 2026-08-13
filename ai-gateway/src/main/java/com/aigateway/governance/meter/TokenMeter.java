package com.aigateway.governance.meter;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.api.dto.ChatCompletion;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.execution.stream.MeteringCallback;
import com.aigateway.execution.stream.MeteringContext;
import com.aigateway.governance.config.GovernanceProperties;
import com.aigateway.governance.model.MeteredUsage;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Token 计量器：把一次请求的真实/近似用量换算成 MeteredUsage（收银机，🖊 H4 核心手敲，对照 11.3）。
 *
 * 两种入口：
 * - meterNonStream：非流式，响应后一次性计量（usage 优先，缺失近似）；
 * - 流式：实现 MeteringCallback，onChunk 增量累计输出 token，
 *   结束 chunk 有 usage 时用真实值覆盖，onFinish 产出 MeteredUsage。
 *
 * 误差说明（学习版 4.5 / 验收 2）：近似算法按字符数与语言系数估算，
 * 中文约 1 字/token、英文约 4 字符/token；流式输出按 chunk 文本增量累计，
 * 结束 chunk 的 usage（mock 与多数真实上游会带）优先，误差 < 15% 达标。
 *
 * TODO H4 手敲清单（未完成前调用对应功能会直接报错）：
 * - meterNonStream()：usage 三字段齐全则用真实值，否则近似补齐；
 * - onChunk() / onFinish()：流式增量累计（tracker 模式，按 requestId 索引）；
 *   结束 chunk usage 覆盖；失败请求成本归零且不写 finished；
 * - takeResult()：从 finished 取结果（无则按文本估算兜底）；
 * - StreamTracker 内部类：accumulate / applyFinalUsage / result。
 * 脚手架已实现：cost / estimateInput / estimateOutputText / responseText（纯换算辅助）。
 */
@Component
public class TokenMeter implements MeteringCallback {

    private final double charsPerToken;
    private final double cjkTokenPerChar;
    private final Map<String, StreamTracker> trackers = new ConcurrentHashMap<>();
    /** 已结算结果：requestId -> MeteredResult（onFinish 写入，GovernanceService.takeResult 取走） */
    private final Map<String, MeteredResult> finished = new ConcurrentHashMap<>();

    /** 结算结果：携带实际执行实例（MeteringContext 里是真实候选，
     *  不是「首个候选」——预算扣减必须按实际走了谁扣） */
    public record MeteredResult(ModelInstance instance, MeteredUsage usage) {}

    public TokenMeter(GovernanceProperties props) {
        this.charsPerToken = props.getEstimation().getCharsPerToken();
        this.cjkTokenPerChar = props.getEstimation().getCjkTokenPerChar();
    }

    /** 非流式计量：usage 三个字段齐全则用真实值，否则近似补齐 */
    public MeteredUsage meterNonStream(ModelInstance inst, ChatRequest request,
                                       ChatCompletion completion) {
        // TODO H4：completion.usage() 且 totalTokens > 0 → 用真实 prompt/completion；
        // 否则 estimated=true，输入 = estimateInput(request)、输出 = estimateOutputText(responseText)
        // → new MeteredUsage(tokenIn, tokenOut, cost(inst, tokenIn, tokenOut), estimated)
        throw new GatewayException(500, "not_implemented",
                "TODO H4：非流式计量未实现（手敲 TokenMeter.meterNonStream）");
    }

    // ── MeteringCallback：流式增量计量 ──

    @Override
    public void onChunk(MeteringContext ctx, ChatChunk chunk) {
        // TODO H4：computeIfAbsent 建 tracker（按 requestId）→ tracker.accumulate(chunk)
        throw new GatewayException(500, "not_implemented",
                "TODO H4：流式增量累计未实现（手敲 TokenMeter.onChunk）");
    }

    @Override
    public MeteredUsage onFinish(MeteringContext ctx, ChatCompletion.Usage usage, boolean success) {
        // TODO H4：remove tracker → usage 真实值覆盖累计 → 失败请求成本归零（不写 finished）→
        // 成功请求 finished.put(requestId, result)；tracker 缺失时按文本估算兜底
        throw new GatewayException(500, "not_implemented",
                "TODO H4：流式结算未实现（手敲 TokenMeter.onFinish）");
    }

    /** 流结束后取最终计量结果（从 finished 移除；无则用 fallback 实例按文本估算兜底） */
    public MeteredResult takeResult(String requestId, ModelInstance fallback, ChatRequest request) {
        // TODO H4：finished.remove(requestId)；无则按输入估算 + 输出 0 兜底
        throw new GatewayException(500, "not_implemented",
                "TODO H4：计量结果取回未实现（手敲 TokenMeter.takeResult）");
    }

    /** 流式累计器：一个请求一个实例（按 requestId 索引）；TODO H4：accumulate / applyFinalUsage / result */
    private final class StreamTracker {
        StreamTracker(MeteringContext ctx) { /* TODO H4：保存 ctx 与累计状态 */ }
    }

    /** 成本核算：单价表（每 1K tokens）× 实际用量 */
    public double cost(ModelInstance inst, long tokenIn, long tokenOut) {
        return tokenIn / 1000.0 * inst.priceIn() + tokenOut / 1000.0 * inst.priceOut();
    }

    private long estimateInput(ChatRequest request) {
        return TokenEstimator.estimateInputTokens(request, charsPerToken, cjkTokenPerChar);
    }

    private long estimateOutputText(String text) {
        return text == null || text.isBlank() ? 0
                : TokenEstimator.estimateTextTokens(text, charsPerToken, cjkTokenPerChar);
    }

    /** 非流式响应全文拼起来（近似输出用） */
    private static String responseText(ChatCompletion completion) {
        if (completion.choices() == null) return "";
        return completion.choices().stream()
                .map(c -> c.message() == null ? "" : c.message().content())
                .filter(s -> s != null)
                .collect(Collectors.joining());
    }
}
