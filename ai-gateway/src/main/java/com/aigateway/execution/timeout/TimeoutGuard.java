package com.aigateway.execution.timeout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *
 * 超时守卫：管两件事——
 * 1. 整条降级链的总时长 deadline（所有尝试共享，重试等待也受它约束）；
 * 2. 流式的"定时关闭"定时器（首字节 / 空闲）：到点后由回调关闭上游连接。
 *
 * 要点）：
 * - 用 System.nanoTime() 算剩余时间，不受系统时钟被修改影响；
 * - 定时器回调只做"关闭连接 / 置标志"这类轻量动作，绝不在定时器线程里做阻塞 IO；
 * - close() 幂等：正常结束、取消、超时都会调，不会重复执行清理。
 */
public final class TimeoutGuard implements AutoCloseable {

    private final long totalDeadlineNanos;          // 创建时刻 + totalMs
    private final ScheduledExecutorService scheduler;
    private final List<ScheduledFuture<?>> timers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public TimeoutGuard(long totalMs, ScheduledExecutorService scheduler) {
        this.totalDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalMs);
        this.scheduler = scheduler;
    }

    /** 整条链是否已超时（每次尝试前先查一次，超时就别再打） */
    public boolean expired() {
        return System.nanoTime() >= totalDeadlineNanos;
    }

    /** 剩余预算（毫秒）；为负按 0 处理 */
    public long remainingMs() {
        long nanos = totalDeadlineNanos - System.nanoTime();
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    /** 注册一个一次性定时器（首字节 / 空闲都用它）；guard 已关闭则忽略 */
    public void scheduleOnce(long delayMs, Runnable action) {
        if(closed.get()) return ;
        ScheduledFuture<?> future = scheduler.schedule(() ->{
            if(!closed.get()) action.run();
        }, delayMs, TimeUnit.MILLISECONDS);
        timers.add(future);
        // close() 与 scheduleOnce 竞态：检查通过后 close() 已清空 timers 并置位，
        // 刚注册的任务不会被取消（回调有 !closed 防护不执行动作，但任务本身泄漏）。
        // 注册后复查一次：已关闭则立即取消，避免泄漏一次性任务。
        if(closed.get()) future.cancel(false);
    }

    /** 取消全部定时器（收到首字节 / 正常结束时调用） */
    public void cancelTimers() {
        timers.forEach(f -> f.cancel(false));
        timers.clear();
    }

    /**
     * 在总时长预算内睡眠（重试退避 / Retry-After 等待的唯一入口）。
     * 预算不够或线程被中断 → false（调用方应放弃，而不是继续等）。
     */
    public boolean sleepWithinBudget(long ms) {
        if(ms <= 0) return true;
        if(remainingMs() < ms) return false;
        try{
            Thread.sleep(ms);
            return true;
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        closed.set(true);
        cancelTimers();
    }
}
