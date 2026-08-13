package com.aigateway.decision.state;

import com.aigateway.infra.config.GatewayProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 状态事件管道（对照详细实施计划第 14 节 S12）：单写者聚合。
 *
 * 为什么需要它（学习版 4.7）：
 * - 请求线程只负责"发事件"（无锁队列 offer，O(1)，不阻塞转发）；
 * - 唯一的写线程按周期把事件批量应用到 ModelStateStore，状态更新与决策读取解耦；
 * - 决策层读的是"最近快照"，不会因为每毫秒高频写而锁竞争。
 *
 * 要点：
 * - flush() 是唯一调用 ModelStateStore.apply 的地方（测试里可手动调用）；
 * - 复用全局 gatewayScheduler（9.2，daemon 线程），不自行创建线程池。
 */
@Component
public class StateEventPipeline {

    private final Queue<StateEvent> queue = new ConcurrentLinkedQueue<>();
    private final ModelStateStore stateStore;
    private final long flushIntervalMs;
    private final ScheduledExecutorService writer;

    public StateEventPipeline(ModelStateStore stateStore, GatewayProperties props,
                              ScheduledExecutorService scheduler) {
        this.stateStore = stateStore;
        this.flushIntervalMs = props.getExecution().getState().getFlushIntervalMs();
        this.writer = scheduler;   // 注入全局 gatewayScheduler（9.2），不自行创建
    }

    @PostConstruct
    void start() {
        writer.scheduleAtFixedRate(this::flush,
                flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    /** 请求线程入口：只入队，不阻塞 */
    public void offer(StateEvent event) {
        queue.offer(event);
    }

    /** 单写者：排空队列并逐个应用（测试可直接调用做确定性断言） */
    public void flush() {
        StateEvent event;
        while ((event = queue.poll()) != null) {
            stateStore.apply(event);
        }
    }
}
