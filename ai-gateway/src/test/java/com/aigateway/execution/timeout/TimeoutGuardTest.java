package com.aigateway.execution.timeout;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 单测（详细实施计划 18.1 H2 行）：超时守卫三件事——
 * 总时长过期判定、预算内睡眠、close() 后定时器不再执行。
 *
 * 依赖说明：ScheduledExecutorService 用真实调度器。
 */
class TimeoutGuardTest {

    @Test
    void totalExpiry_shouldReportExpiredAndZeroRemaining() throws InterruptedException {
        try (TimeoutGuard guard = new TimeoutGuard(50, Executors.newScheduledThreadPool(1))) {
            Thread.sleep(80);
            assertThat(guard.expired()).isTrue();
            assertThat(guard.remainingMs()).isZero();
        }
    }

    @Test
    void sleepWithinBudget_shouldRespectBudget() {
        try (TimeoutGuard guard = new TimeoutGuard(60_000, Executors.newScheduledThreadPool(1))) {
            // 预算足够 → 正常睡完返回 true
            assertThat(guard.sleepWithinBudget(10)).isTrue();
            // 预算不足 → 不睡直接返回 false（调用方应放弃）
            assertThat(guard.sleepWithinBudget(61_000)).isFalse();
        }
    }

    @Test
    void timersCancelled_afterClose() throws InterruptedException {
        try (TimeoutGuard guard = new TimeoutGuard(60_000, Executors.newScheduledThreadPool(1))) {
            AtomicBoolean fired = new AtomicBoolean();
            guard.scheduleOnce(20, () -> fired.set(true));
            guard.close();
            Thread.sleep(60);
            assertThat(fired).isFalse();
        }
    }
}
