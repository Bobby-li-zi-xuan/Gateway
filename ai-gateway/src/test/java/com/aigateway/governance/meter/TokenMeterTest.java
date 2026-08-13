package com.aigateway.governance.meter;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.api.dto.ChatCompletion;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.execution.stream.MeteringContext;
import com.aigateway.governance.config.GovernanceProperties;
import com.aigateway.governance.model.MeteredUsage;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * H4 单测骨架（对照《版本4-详细实施计划》11.4）。
 * 手敲完成 TokenMeter 后删除 @Disabled 即可运行。
 */
@Disabled("TODO H4：手敲 TokenMeter 完成后启用")
class TokenMeterTest {

    @Test
    void nonStream_usesUpstreamUsage_whenPresent() {
        TokenMeter meter = new TokenMeter(testProps());
        ModelInstance inst = instance(0.001, 0.002);
        ChatCompletion completion = new ChatCompletion("id", "chat.completion", 0, "m",
                List.of(), new ChatCompletion.Usage(100, 200, 300));

        MeteredUsage u = meter.meterNonStream(inst, request("你好"), completion);
        assertThat(u.tokenIn()).isEqualTo(100);
        assertThat(u.tokenOut()).isEqualTo(200);
        assertThat(u.estimated()).isFalse();
        assertThat(u.cost()).isCloseTo(100 / 1000.0 * 0.001 + 200 / 1000.0 * 0.002, within(1e-9));
    }

    @Test
    void nonStream_fallsBackToEstimate_whenUsageMissing() {
        TokenMeter meter = new TokenMeter(testProps());
        ModelInstance inst = instance(0.001, 0.002);
        ChatCompletion completion = new ChatCompletion("id", "chat.completion", 0, "m",
                List.of(new ChatCompletion.Choice(0,
                        new ChatCompletion.Choice.Message("assistant", "你好，我是助手"), "stop")),
                null);

        MeteredUsage u = meter.meterNonStream(inst, request("你好"), completion);
        assertThat(u.estimated()).isTrue();
        assertThat(u.tokenIn()).isGreaterThanOrEqualTo(1);     // “你好”约 2 token
        assertThat(u.tokenOut()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void stream_accumulatesDeltas() {
        TokenMeter meter = new TokenMeter(testProps());
        MeteringContext ctx = ctx("rid-1");
        ChatChunk c1 = chunk("你");
        ChatChunk c2 = chunk("好");
        meter.onChunk(ctx, c1);
        meter.onChunk(ctx, c2);

        // 结束 chunk 带真实 usage → 覆盖累计
        MeteredUsage u = meter.onFinish(ctx, new ChatCompletion.Usage(10, 5, 15), true);
        assertThat(u.tokenOut()).isEqualTo(5);
        assertThat(u.estimated()).isFalse();
    }

    @Test
    void failureRequest_hasZeroCost() {
        TokenMeter meter = new TokenMeter(testProps());
        MeteringContext ctx = ctx("rid-2");
        meter.onChunk(ctx, chunk("好"));
        MeteredUsage u = meter.onFinish(ctx, null, false);      // 失败
        assertThat(u.cost()).isZero();                          // 失败不扣钱
    }

    // —— 测试辅助 ——
    private static GovernanceProperties testProps() {
        GovernanceProperties props = new GovernanceProperties();
        props.getEstimation().setCharsPerToken(4.0);
        props.getEstimation().setCjkTokenPerChar(1.0);
        return props;
    }

    private static ModelInstance instance(double priceIn, double priceOut) {
        return new ModelInstance("mock-a:qwen", "qwen", "mock-a", "qwen",
                1, null, priceIn, priceOut, 0.5, 1000);
    }

    private static ChatRequest request(String content) {
        return new ChatRequest("qwen",
                List.of(new ChatRequest.Message("user", content)), false, null, null);
    }

    private static MeteringContext ctx(String rid) {
        return new MeteringContext(rid, instance(0.001, 0.002), request("你好"), "agw_00000001");
    }

    private static ChatChunk chunk(String delta) {
        return new ChatChunk("id-1", "chat.completion.chunk", 0, "qwen",
                List.of(new ChatChunk.ChunkChoice(0,
                        new ChatChunk.ChunkChoice.Delta(delta), null)),
                null, null);   // usage / error：V3 扩展字段，测试辅助不关心，置空
    }
}
