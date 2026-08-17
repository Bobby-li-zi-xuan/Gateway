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
 * Token 计量器：把一次请求的真实/近似用量换算成 MeteredUsage
 *
 * 两种入口：
 * - meterNonStream：非流式，响应后一次性计量（usage 优先，缺失近似）；
 * - 流式：实现 MeteringCallback，onChunk 增量累计输出 token，
 *   结束 chunk 有 usage 时用真实值覆盖，onFinish 产出 MeteredUsage。
 *
 * 误差说明（学习版 4.5 / 验收 2）：近似算法按字符数与语言系数估算，
 * 中文约 1 字/token、英文约 4 字符/token；流式输出按 chunk 文本增量累计，
 * 结束 chunk 的 usage（mock 与多数真实上游会带）优先，误差 < 15% 达标。

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
        // completion.usage() 且 totalTokens > 0 → 用真实 prompt/completion；
        // 否则 estimated=true，输入 = estimateInput(request)、输出 = estimateOutputText(responseText)
        // → new MeteredUsage(tokenIn, tokenOut, cost(inst, tokenIn, tokenOut), estimated)
        boolean estimated = false;
        long tokenIn;
        long tokenOut;
        if(completion.usage() != null && completion.usage().totalTokens() > 0){
            tokenIn = completion.usage().promptTokens();
            tokenOut = completion.usage().completionTokens();
        } else {
            estimated = true;
            tokenIn = estimateInput(request);
            tokenOut = estimateOutputText(responseText(completion));
        }

        return new MeteredUsage(tokenIn, tokenOut, cost(inst, tokenIn, tokenOut), estimated);
    }

    // ── MeteringCallback：流式增量计量 ──

    @Override
    public void onChunk(MeteringContext ctx, ChatChunk chunk) {
        // computeIfAbsent 建 tracker（按 requestId）→ tracker.accumulate(chunk)
        StreamTracker tracker = trackers.computeIfAbsent(ctx.requestId(), k -> new StreamTracker(ctx));
        tracker.accumulate(chunk);          // 增量累计 + 结束chunk usage 覆盖
    }

    @Override
    public MeteredUsage onFinish(MeteringContext ctx, ChatCompletion.Usage usage, boolean success) {
        // remove tracker → usage 真实值覆盖累计 → 失败请求成本归零（不写 finished）→
        // 成功请求 finished.put(requestId, result)；tracker 缺失时按文本估算兜底
        StreamTracker tracker = trackers.remove(ctx.requestId());
        MeteredResult result;
        if(tracker == null){
            // 异常路径兜底：按请求文本估算输入，输出0（失败请求不扣预算，仅留痕）
            long in = estimateInput(ctx.request());
            result = new MeteredResult(ctx.instance(), new MeteredUsage(in, 0, cost(ctx.instance(), in, 0), true));
        }else{
            if(usage != null && usage.totalTokens() > 0){
                // 真实 usage覆盖累计值
                tracker.applyFinalUsage(usage);
            }
            MeteredUsage u = tracker.result(ctx.instance());
            if(!success){
                // 失败请求：仍留痕(result=failure),但不扣预算(成本归零)
                u = new MeteredUsage(u.tokenIn(), u.tokenOut(), 0, u.estimated());
            }
            // 真实候选实例
            result = new MeteredResult(ctx.instance(), u);
        }
        if(success){
            finished.put(ctx.requestId(), result);
        }
        // 失败请求不写finished: catch 路径只留痕不结算
        return result.usage();
    }

    /** 流结束后取最终计量结果（从 finished 移除；无则用 fallback 实例按文本估算兜底） */
    public MeteredResult takeResult(String requestId, ModelInstance fallback, ChatRequest request) {
        // finished.remove(requestId)；无则按输入估算 + 输出 0 兜底
        MeteredResult result = finished.remove(requestId);
        if(result == null){
            long in = estimateInput(request);
            result = new MeteredResult(fallback, new MeteredUsage(in, 0, cost(fallback, in, 0), true));
        }
        return result;
    }

    /** 流式累计器：一个请求一个实例（按 requestId 索引）；
     * accumulate / applyFinalUsage / result */
    private final class StreamTracker {
        private final MeteringContext ctx;
        private long tokenOut;
        private boolean hasRealUsage;

        StreamTracker(MeteringContext ctx) { this.ctx = ctx; }

        void accumulate(ChatChunk chunk) {
            if (chunk.choices() == null) return;
            for (ChatChunk.ChunkChoice choice : chunk.choices()) {
                if (choice.delta() != null && choice.delta().content() != null
                        && !choice.delta().content().isEmpty()) {
                    tokenOut += TokenEstimator.estimateTextTokens(
                            choice.delta().content(), charsPerToken, cjkTokenPerChar);
                }
            }
        }

        void applyFinalUsage(ChatCompletion.Usage usage) {
            tokenOut = usage.completionTokens();       // 真实值覆盖近似累计
            hasRealUsage = true;
        }

        MeteredUsage result(ModelInstance inst) {
            long in = estimateInput(ctx.request());
            return new MeteredUsage(in, tokenOut,
                    cost(inst, in, tokenOut), !hasRealUsage);
        }
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
