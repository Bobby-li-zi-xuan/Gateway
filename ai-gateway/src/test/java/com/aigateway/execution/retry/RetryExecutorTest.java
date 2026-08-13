package com.aigateway.execution.retry;

import com.aigateway.execution.model.Failure;
import com.aigateway.execution.model.FailureType;
import com.aigateway.execution.model.RetryPolicy;
import com.aigateway.execution.model.UpstreamCallException;
import com.aigateway.execution.timeout.TimeoutGuard;
import com.aigateway.observability.GatewayMetrics;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * H1 单测（骨架 + maxAttemptsPerCandidate 用例）：
 * maxAttemptsPerCandidate 控制同实例重试次数：默认 1 = 不重试；配置 N = 最多 N-1 次重试。
 *
 * 待写用例（对照计划 18.1 表格）：
 * 1. http429_shouldWaitRetryAfter：429 + Retry-After → 等待 min(retryAfter, maxRetryAfter)；
 *    无 Retry-After header 时不等待；
 * 2. http4xx_shouldNotRetry：4xx 原样抛出，attempt 只调用一次；
 * 3. budgetExhausted_shouldThrowTimeoutTotal：sleepWithinBudget=false → 抛 TIMEOUT_TOTAL。
 *
 * 依赖说明：构造 RetryExecutor 只需 mock GatewayMetrics；
 * TimeoutGuard 用真实实例（预算 60s 远大于用例等待时间）。
 */
class RetryExecutorTest {

    private static final Failure CONNECTION_FAILURE =
            new Failure(FailureType.CONNECTION, "连接失败", -1, true, -1);

    private final GatewayMetrics metrics = mock(GatewayMetrics.class);
    private final RetryExecutor executor = new RetryExecutor(metrics);
    private final TimeoutGuard guard = new TimeoutGuard(60_000, Executors.newScheduledThreadPool(1));

    /** maxAttemptsPerCandidate=1（默认）：连接失败也直接上抛，attempt 只调 1 次 */
    @Test
    void maxAttemptsOne_shouldNotRetry() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> executor.execute(policy(1), guard, "mock-a:qwen", () -> {
            calls.incrementAndGet();
            throw new UpstreamCallException(CONNECTION_FAILURE);
        })).isInstanceOf(UpstreamCallException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    /** maxAttemptsPerCandidate=2：连接失败重试 1 次，第二次成功即返回 */
    @Test
    void maxAttemptsTwo_shouldRetryOnceThenSucceed() throws UpstreamCallException {
        AtomicInteger calls = new AtomicInteger();
        String result = executor.execute(policy(2), guard, "mock-a:qwen", () -> {
            if (calls.incrementAndGet() == 1) throw new UpstreamCallException(CONNECTION_FAILURE);
            return "ok";
        });
        assertThat(calls.get()).isEqualTo(2);
        assertThat(result).isEqualTo("ok");
    }

    /** maxAttemptsPerCandidate=2：两次都失败 → 第二次失败原样上抛（不继续重试） */
    @Test
    void maxAttemptsTwo_shouldStopAfterSecondFailure() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> executor.execute(policy(2), guard, "mock-a:qwen", () -> {
            calls.incrementAndGet();
            throw new UpstreamCallException(CONNECTION_FAILURE);
        })).isInstanceOf(UpstreamCallException.class);
        assertThat(calls.get()).isEqualTo(2);
    }

    private static RetryPolicy policy(int maxAttempts) {
        return new RetryPolicy(maxAttempts, true, true, 5_000,
                10, 2_000, 0.0, Set.of(429, 500, 502, 503, 504));
    }
}
