package com.aigateway.execution.circuit;

import java.util.Arrays;

import com.aigateway.execution.model.CircuitBreakerConfig;
import com.aigateway.execution.model.CircuitState;

/**
 * ⚠️ 手敲 H3（详细实施计划第 10 节 S8）——本类全部逻辑需手敲，方法体当前抛 TODO 异常。
 *
 * 单实例熔断器：滑动窗口（环形数组）统计最近 N 次结果。
 *
 * 手敲要点（对照计划 10.2）：
 * - 环形数组：写入 O(1)，旧样本自动被新样本挤出——这就是"滑动窗口"；
 * - 所有状态转换在 synchronized(lock) 内完成：熔断是全局性决策，不允许并发竞态
 *   把"打开"和"关闭"同时写进去；
 * - OPEN 期的拒绝请求不进入窗口（它们没打上游，不是有效样本）；
 * - 半开态：探测成功 → CLOSED 并清空窗口；失败 → OPEN 重新计时。
 */
public final class CircuitBreaker {

    private final String instanceId;
    private final CircuitBreakerConfig config;
    private final Object lock = new Object();

    private CircuitState state = CircuitState.CLOSED;
    private final boolean[] failures;   // 环形窗口：true = 失败
    private final boolean[] slows;      // 环形窗口：true = 慢调用
    private int index;                  // 下一个写入位
    private int filled;                 // 已填充样本数
    private int failureCount;
    private int slowCount;
    private long openUntilMs;
    private int halfOpenPermits;

    public CircuitBreaker(String instanceId, CircuitBreakerConfig config) {
        this.instanceId = instanceId;
        this.config = config;
        this.failures = new boolean[config.windowSize()];
        this.slows = new boolean[config.windowSize()];
    }

    /** 是否放行本次请求（执行前调用） */
    public boolean allowRequest() {
        synchronized(lock){
            return switch(state){
                case CLOSED -> true;
                case OPEN -> {
                    //打卡期到期 ->  转半开并发放探测配额
                    if(System.currentTimeMillis() >= openUntilMs){
                        state = CircuitState.HALF_OPEN;
                        halfOpenPermits = config.halfOpenMaxRequests();
                        yield true;
                    }
                    yield false;
                }
                case HALF_OPEN -> {
                    if(halfOpenPermits > 0){
                        halfOpenPermits --;
                        yield true;
                    }
                    yield false;
                }
            };
        }
    }

    /**
     * 记录一次真实调用结果（只有被放行的请求才会调用这里）。
     *
     * @param success          是否成功
     * @param latencyMs        耗时
     * @param slowThresholdMs  慢调用阈值（null 表示不判定慢调用）
     * @return 本次记录后的状态转换（from → to）；无转换时 from == to。
     *         在锁内判定转换，调用方据此发布事件——避免注册表在锁外比较状态，
     *         并发下同一转换被重复发布（两个线程都读到旧状态、都发出事件）。
     */
    public StateTransition record(boolean success, long latencyMs, Long slowThresholdMs) {
        boolean slow = slowThresholdMs != null && latencyMs >= slowThresholdMs;
        synchronized(lock){
            //半开态：探测结果直接决定去向，不进窗口
            if(state == CircuitState.HALF_OPEN){
                resetWindow();
                if(success){
                    state = CircuitState.CLOSED;
                }else{
                    open();
                }
                return new StateTransition(CircuitState.HALF_OPEN, state);
            }
            push(success, slow);
            // 样本足够且失败率 / 慢调用率任一超阈值 -> 打开
            if(filled >= config.minimumRequests() && (failureRate() >= config.failureRateThreshold()
                || slowRate() >= config.slowCallRateThreshold())){
                open();
                return new StateTransition(CircuitState.CLOSED, CircuitState.OPEN);
            }
            return new StateTransition(state, state);
        }
    }

    /** 状态转换快照（record 返回值）：from == to 表示无转换 */
    public record StateTransition(CircuitState from, CircuitState to) {}

    /** 环形数组写入：先减去旧样本贡献，再加新样本 */
    private void push(boolean success, boolean slow){
        if(filled < config.windowSize()) filled ++;
        if(failures[index]) failureCount --;
        if(slows[index]) slowCount --;

        failures[index] = !success;
        slows[index] = slow;
        if(!success) failureCount ++;
        if(slow) slowCount ++;
        index = (index + 1) % config.windowSize();
    }

    private void open(){
        state = CircuitState.OPEN;
        openUntilMs = System.currentTimeMillis() + config.openDurationMs();
        halfOpenPermits = 0;  
    }

    private void resetWindow(){
        Arrays.fill(failures, false);
        Arrays.fill(slows, false);
        index = 0;
        filled = 0;
        failureCount = 0;
        slowCount = 0;
        
    }

    private double failureRate() {
        return filled == 0 ? 0 : (double) failureCount / filled;
    }

    private double slowRate() {
        return filled == 0 ? 0 : (double) slowCount / filled;
    }

    public CircuitState state() {
        synchronized(lock){return state;}
    }

    public String instanceId() { return instanceId; }
}
