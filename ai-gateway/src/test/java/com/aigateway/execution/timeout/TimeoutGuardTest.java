package com.aigateway.execution.timeout;

/**
 * ⚠️ H2 单测骨架（详细实施计划 18.1 H2 行）：
 * TimeoutGuard 未手敲前（方法抛 TODO 异常）本组用例无法通过，H2 完成后逐个启用。
 *
 * 待写用例（对照计划 18.1 表格）：
 * 1. totalExpiry_shouldReportExpiredAndZeroRemaining：总时长过期 → expired()=true、
 *    remainingMs()=0（用短时长构造，如 totalMs=50 + sleep 60）；
 * 2. sleepWithinBudget_shouldRespectBudget：预算足够 → sleep 成功返回 true；
 *    预算不足 → 返回 false；
 * 3. timersCancelled_afterClose：close() 后 scheduleOnce 不再执行回调
 *    （AtomicBoolean 标记 + 短延迟 + 短暂等待断言）。
 *
 * 依赖说明：ScheduledExecutorService 用真实调度器（Executors.newScheduledThreadPool）。
 */
class TimeoutGuardTest {

    // TODO H2: 构造 TimeoutGuard（短 totalMs 避免测试慢）并编写上述用例
}
