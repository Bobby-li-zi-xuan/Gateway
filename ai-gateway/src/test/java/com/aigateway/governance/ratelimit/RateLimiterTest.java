package com.aigateway.governance.ratelimit;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 单测骨架（对照《版本4-详细实施计划》9.3）。
 * 手敲完成 RateLimiter 后删除 @Disabled 即可运行。
 */
@Disabled("TODO H2：手敲 RateLimiter 完成后启用")
class RateLimiterTest {

    @Test
    void sixthConcurrentRequest_rejected() {
        RateLimiter limiter = new RateLimiter();
        RateLimitRule rule = new RateLimitRule(RateLimitKey.token("tok-1"), 1000, 5, false);

        for (int i = 0; i < 5; i++) assertThat(limiter.tryAcquire(rule, 1)).isTrue();
        assertThat(limiter.tryAcquire(rule, 1)).isFalse();          // 第 6 个 → 429
        assertThat(limiter.windowCount(RateLimitKey.token("tok-1"), 1000)).isEqualTo(5);
    }

    @Test
    void windowRollsOver_afterTimeout() throws InterruptedException {
        RateLimiter limiter = new RateLimiter();
        RateLimitRule rule = new RateLimitRule(RateLimitKey.token("tok-2"), 100, 2, false);

        limiter.tryAcquire(rule, 1);
        limiter.tryAcquire(rule, 1);
        assertThat(limiter.tryAcquire(rule, 1)).isFalse();
        Thread.sleep(120);                                          // 窗口过去
        assertThat(limiter.tryAcquire(rule, 1)).isTrue();           // 计数恢复
    }

    @Test
    void tokenMode_placeholderThenAdjust() {
        RateLimiter limiter = new RateLimiter();
        RateLimitRule rule = new RateLimitRule(RateLimitKey.token("tok-3"), 1000, 100, true);

        assertThat(limiter.tryAcquire(rule, 60)).isTrue();          // 预扣预估 60
        assertThat(limiter.tryAcquire(rule, 50)).isFalse();         // 60+50 > 100
        limiter.adjust(RateLimitKey.token("tok-3"), 1000, -20);     // 实际 40：回吐 20
        assertThat(limiter.tryAcquire(rule, 50)).isTrue();          // 40+50 ≤ 100
    }

    @Test
    void dimensions_areIsolated() {
        RateLimiter limiter = new RateLimiter();
        RateLimitRule a = new RateLimitRule(RateLimitKey.token("tok-a"), 1000, 1, false);
        RateLimitRule b = new RateLimitRule(RateLimitKey.token("tok-b"), 1000, 1, false);
        assertThat(limiter.tryAcquire(a, 1)).isTrue();
        assertThat(limiter.tryAcquire(b, 1)).isTrue();              // 互不影响
        assertThat(limiter.tryAcquire(a, 1)).isFalse();
    }
}
