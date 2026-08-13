package com.aigateway.governance.ledger;

import com.aigateway.core.exception.GatewayException;
import com.aigateway.governance.budget.BudgetManager;
import com.aigateway.governance.config.GovernanceProperties;
import com.aigateway.governance.model.UsageRecord;
import com.aigateway.governance.persistence.UsageDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 用量账本：内存队列 + 定时批量落库（🖊 H6：逻辑全部手敲，对照 13.2 / 学习版 4.1）。
 *
 * 设计语义：
 * 1. record() 只入队：请求线程零磁盘 IO，无锁入队不阻塞转发；
 * 2. flush() 批量写库：一次事务写 N 条（batchSize 上限），失败整批放回队尾重试
 *   （代价：重复窗口内最多丢一批，可接受——账本允许最终一致）；
 * 3. 顺带触发 BudgetManager.flushUsed()：预算 used 快照与用量同频对账；
 * 4. 复用全局 gatewayScheduler（V3 的 gw-timer），不另起线程池。
 *
 * TODO H6 手敲清单（未完成前调用对应功能会直接报错）：
 * - 构造器：scheduler.scheduleAtFixedRate(this::flush, intervalMs, intervalMs, MILLISECONDS)；
 * - record()：queue.offer（请求线程入口，失败请求也留痕 result=failure）；
 * - flush()：drain(batchSize) → dao.batchInsert（失败整批放回队尾重试，跳过对账）→
 *   budgetManager.flushUsed() 顺带对账；
 * - drain()：从队头取最多 max 条（不删除——失败时整体放回）。
 */
@Component
public class UsageLedger {

    private static final Logger log = LoggerFactory.getLogger(UsageLedger.class);

    private final UsageDao dao;
    private final BudgetManager budgetManager;
    private final int batchSize;
    private final Queue<UsageRecord> queue = new ConcurrentLinkedQueue<>();

    public UsageLedger(UsageDao dao, BudgetManager budgetManager,
                       GovernanceProperties props, ScheduledExecutorService scheduler) {
        this.dao = dao;
        this.budgetManager = budgetManager;
        this.batchSize = props.getUsageFlush().getBatchSize();
        // TODO H6：定时批量落库（见类注释清单）
    }

    /** 请求线程入口：只入队（失败请求也留痕，result=failure） */
    public void record(UsageRecord r) {
        // TODO H6：queue.offer(r)
        throw new GatewayException(500, "not_implemented",
                "TODO H6：用量入队未实现（手敲 UsageLedger.record）");
    }

    /** 批量落库（定时线程调用；测试可直接调用做确定性断言） */
    public void flush() {
        // TODO H6：见类注释清单（drain → batchInsert → 失败放回 → budgetManager.flushUsed）
        throw new GatewayException(500, "not_implemented",
                "TODO H6：批量落库未实现（手敲 UsageLedger.flush）");
    }

    /** 从队头取最多 max 条（不删除 —— 失败时整体放回） */
    private java.util.List<UsageRecord> drain(int max) {
        // TODO H6：queue.poll 循环取最多 max 条
        throw new GatewayException(500, "not_implemented",
                "TODO H6：批量取队未实现（手敲 UsageLedger.drain）");
    }

    /** 待落库条数（指标用） */
    public int pending() { return queue.size(); }
}
