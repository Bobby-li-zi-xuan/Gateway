package com.aigateway.execution.stream;

/**
 * ⚠️ H6 单测骨架（详细实施计划 18.1 H6 行）：
 * StreamProxy 未手敲前（streamChain 抛 TODO 异常）本组用例无法通过，H6 完成后逐个启用。
 *
 * 待写用例（对照计划 18.1 表格与第 18.1 节关键测试示例）：
 * 1. sseNormalization_shouldFillMissingFields：缺 id/created/model 时补齐；object 固定；
 * 2. errorEvent_shouldPassThrough：上游 error chunk → 客户端收到统一错误体后结束；
 * 3. failureAfterFirstByte_shouldNotDegrade：已 started 后失败 → 502 stream_interrupted，
 *    下一个候选不被调用（计划 18.1 已给出示例代码，对照抄写即可）；
 * 4. clientDisconnect_shouldCancelSession：consumer 抛 ClientDisconnectedException →
 *    connector.stream 返回的 session.cancel() 被调用；无 failure 指标。
 *
 * 依赖说明：9 个依赖全部 mock；connector.stream(...) 用 mock 返回 StreamSession
 * （或按用例直接抛 ClientDisconnectedException / UpstreamCallException）。
 */
class StreamProxyTest {

    // TODO H6: mock 9 个依赖并构造 StreamProxy；编写上述用例
}
