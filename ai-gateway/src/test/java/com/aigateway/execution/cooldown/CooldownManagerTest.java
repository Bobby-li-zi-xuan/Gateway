package com.aigateway.execution.cooldown;

import com.aigateway.execution.model.CooldownConfig;

/**
 * ⚠️ H4 单测骨架（详细实施计划 18.1 H4 行）：
 * CooldownManager 未手敲前（方法抛 TODO 异常）本组用例无法通过，H4 完成后逐个启用。
 *
 * 待写用例（对照计划 18.1 表格）：
 * 1. consecutiveFailures_shouldEnterCooldown：第 N 次失败后 isCooling()=true；
 * 2. expiry_shouldRecoverAndDoubleOnProbeFailure：冷却到期 → isCooling()=false（进入探测期）；
 *    探测期失败 → 重新冷却且时长翻倍（≤ maxCooldownMs）；
 * 3. success_shouldResetCounters：recordSuccess 后计数与探测态复位、时长恢复基准值。
 *
 * 依赖说明：new CooldownManager(config) 纯构造，无 Spring 依赖；
 * 可传入 Listener 断言冷却进出回调。
 */
class CooldownManagerTest {

    private static final CooldownConfig CONFIG = new CooldownConfig(
            5, 30_000, 300_000, true);

    // TODO H4: @Test void consecutiveFailures_shouldEnterCooldown() { ... }
    // TODO H4: @Test void expiry_shouldRecoverAndDoubleOnProbeFailure() { ... }
    // TODO H4: @Test void success_shouldResetCounters() { ... }
}
