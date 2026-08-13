package com.aigateway.execution.cooldown;

import com.aigateway.execution.model.CooldownConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H4 单测（详细实施计划 18.1 H4 行）：冷却三件事——
 * 连续失败达标进入冷却、到期恢复 + 探测失败翻倍、成功清零。
 *
 * 依赖说明：new CooldownManager(config) 纯构造，无 Spring 依赖；
 * 传入 Listener 捕获冷却进出回调断言时长。
 */
class CooldownManagerTest {

    private static final CooldownConfig CONFIG = new CooldownConfig(
            5, 30_000, 300_000, true);

    /** 连续失败达阈值 → 进入冷却；未达标不冷却 */
    @Test
    void consecutiveFailures_shouldEnterCooldown() {
        CooldownManager cm = new CooldownManager(CONFIG);
        for (int i = 0; i < 4; i++) {
            cm.recordFailure("mock-a");
            assertThat(cm.isCooling("mock-a")).isFalse();
        }
        cm.recordFailure("mock-a");  // 第 5 次达标
        assertThat(cm.isCooling("mock-a")).isTrue();
    }

    /** 冷却到期 → 探测期；探测期失败 → 重新冷却且时长翻倍（Listener 捕获时长断言） */
    @Test
    void expiry_shouldRecoverAndDoubleOnProbeFailure() throws InterruptedException {
        List<Long> cooldownMs = new ArrayList<>();
        // 短配置（冷却 100ms、翻倍上限 1000ms）：毫秒精度下 1ms 不可靠（recordFailure 与
        // isCooling 之间的真实时间可能超过冷却时长导致"还没断言就到期"）
        CooldownConfig shortCfg = new CooldownConfig(1, 100, 1_000, true);
        CooldownManager cm = new CooldownManager(shortCfg,
                (channelId, cooling, ms) -> { if (cooling) cooldownMs.add(ms); });

        cm.recordFailure("mock-a");              // 第 1 次失败即冷却（100ms）
        assertThat(cm.isCooling("mock-a")).isTrue();

        Thread.sleep(150);                       // 冷却到期
        assertThat(cm.isCooling("mock-a")).isFalse();  // 惰性迁移：到期 → 探测期

        cm.recordFailure("mock-a");              // 探测期失败 → 翻倍重新冷却（200ms）
        assertThat(cm.isCooling("mock-a")).isTrue();
        assertThat(cooldownMs).containsExactly(100L, 200L);  // 时长翻倍
    }

    /** recordSuccess 清零计数与探测态，退出冷却并恢复基准时长 */
    @Test
    void success_shouldResetCounters() {
        CooldownManager cm = new CooldownManager(CONFIG);
        for (int i = 0; i < 5; i++) cm.recordFailure("mock-a");
        assertThat(cm.isCooling("mock-a")).isTrue();

        cm.recordSuccess("mock-a");
        assertThat(cm.isCooling("mock-a")).isFalse();
        // 恢复后第一次失败只累计计数，不会立刻进入冷却（计数已清零）
        cm.recordFailure("mock-a");
        assertThat(cm.isCooling("mock-a")).isFalse();
    }
}
