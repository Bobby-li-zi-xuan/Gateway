package com.aigateway.decision.log;

import com.aigateway.decision.model.RoutingDecision;
import com.aigateway.infra.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 决策日志环形缓冲：保留最近 N 条 RoutingDecision（含打分明细）。
 * 固定内存上限、O(1) 插入与淘汰；版本 5 会替换/升级为可查询的持久化存储。
 */
@Component
public class DecisionLogStore {

    private final int maxEntries;                    // gateway.decision.logSize
    private final Deque<RoutingDecision> ring = new ConcurrentLinkedDeque<>();

    public DecisionLogStore(GatewayProperties props) {
        this.maxEntries = props.getDecision().getLogSize();
    }

    /** 新决策放头部；超出容量从尾部淘汰（固定内存、自动淘汰旧数据） */
    public void record(RoutingDecision decision) {
        ring.addFirst(decision);
        while (ring.size() > maxEntries) {
            ring.pollLast();
        }
    }

    /** 最近 N 条（新 → 旧） */
    public List<RoutingDecision> recent(int limit) {
        return ring.stream().limit(Math.max(0, Math.min(limit, maxEntries))).toList();
    }
}
