package com.aigateway.api.controller;

import com.aigateway.decision.log.DecisionLogStore;
import com.aigateway.decision.model.RoutingDecision;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 决策调试端点：GET /v1/debug/decisions?limit=10。
 * 只读展示最近决策（含打分明细），版本 5 由 Admin API 取代。
 */
@RestController
public class DecisionDebugController {

    private final DecisionLogStore decisionLogStore;

    public DecisionDebugController(DecisionLogStore decisionLogStore) {
        this.decisionLogStore = decisionLogStore;
    }

    @GetMapping("/v1/debug/decisions")
    public Map<String, Object> recent(@RequestParam(defaultValue = "10") int limit) {
        List<RoutingDecision> list = decisionLogStore.recent(limit);
        return Map.of("count", list.size(), "decisions", list);
    }
}
