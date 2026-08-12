package com.aigateway.execution.fallback;

/**
 * ⚠️ H5 单测骨架（详细实施计划 18.1 H5 行）：
 * FallbackChainExecutor 未手敲前（execute 抛 TODO 异常）本组用例无法通过，H5 完成后逐个启用。
 *
 * 待写用例（对照计划 18.1 表格）：
 * 1. primaryFails_shouldDegradeToNextCandidate：主选失败 → 结果来自链上第二个候选；
 *    fallback 指标 +1；
 * 2. circuitOrCooldownSkip_shouldSkipCandidate：熔断/冷却命中的候选不发起调用；
 *    全部跳过 → 503 circuit_open / cooldown_active（且未发起任何上游调用）；
 * 3. totalTimeout_shouldReturn504：各候选耗时总和超 totalMs → 504 timeout_total。
 *
 * 依赖说明：9 个依赖全部 mock（connector / circuitBreakers / cooldowns / retryExecutor /
 * policyManager / stateStore / stateEvents / metrics / scheduler）；
 * 候选链构造：ExecutionChainResolver 返回 List<ModelInstance>（ModelInstance 直造即可）。
 */
class FallbackChainExecutorTest {

    // TODO H5: mock 9 个依赖并构造 FallbackChainExecutor；编写上述用例
}
