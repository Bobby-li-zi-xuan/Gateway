package com.aigateway.execution.retry;

/**
 * ⚠️ H1 单测骨架（详细实施计划 18.1 H1 行）：
 * RetryExecutor 未手敲前（execute 抛 TODO 异常）本组用例无法通过，H1 完成后逐个启用。
 *
 * 待写用例（对照计划 18.1 表格）：
 * 1. connectionFailure_shouldRetrySameInstanceOnce：连接失败 → 第二次调用成功即返回；
 *    超过一次不再重试（attempt 最多被调 2 次）；
 * 2. http429_shouldWaitRetryAfter：429 + Retry-After → 等待 min(retryAfter, maxRetryAfter)；
 *    无 Retry-After header 时不等待；
 * 3. http4xx_shouldNotRetry：4xx 原样抛出，attempt 只调用一次；
 * 4. budgetExhausted_shouldThrowTimeoutTotal：sleepWithinBudget=false → 抛 TIMEOUT_TOTAL。
 *
 * 依赖说明：构造 RetryExecutor 只需 mock GatewayMetrics；
 * TimeoutGuard 用真实实例（H2 完成后可用）或 mock。
 */
class RetryExecutorTest {

    // TODO H1: private final GatewayMetrics metrics = mock(GatewayMetrics.class);
    // TODO H1: private final RetryExecutor executor = new RetryExecutor(metrics);

    // TODO H1: @Test void connectionFailure_shouldRetrySameInstanceOnce() { ... }
    // TODO H1: @Test void http429_shouldWaitRetryAfter() { ... }
    // TODO H1: @Test void http4xx_shouldNotRetry() { ... }
    // TODO H1: @Test void budgetExhausted_shouldThrowTimeoutTotal() { ... }
}
