package com.aigateway.decision.scheduler;

import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.decision.model.Policy;
import com.aigateway.decision.model.RoutingDecision;
import com.aigateway.decision.model.ScoringDetail;
import com.aigateway.decision.policy.PolicyManager;
import com.aigateway.plugin.context.Signals;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 调度器：按策略类型分派（多目标 / 加权随机 / 条件路由），输出 RoutingDecision。
 *
 * 🖊 手敲 H4：本类为必手敲模块，按《版本2-详细实施计划》第 11 节实现。
 * 未完成前调用 {@link #decide} 会直接报错。
 *
 * 策略类（MultiObjectiveStrategy / WeightedRandomStrategy / ConditionalStrategy）
 * 已由脚手架提供，手敲时直接组合它们即可。
 */
@Component
public class Scheduler {

    private final PolicyManager policyManager; // CONDITIONAL 未命中时回退查询

    public Scheduler(PolicyManager policyManager) {
        this.policyManager = policyManager;
    }

    /** TODO H4：按《版本2-详细实施计划》第 11 节手敲实现 */
    public RoutingDecision decide(String requestId, String alias, Policy policy,
                                  List<ModelInstance> candidates,
                                  List<ScoringDetail> details,
                                  Signals signals) {
        throw new GatewayException(500, "not_implemented",
                "TODO H4：Scheduler 未实现（按《版本2-详细实施计划》第 11 节手敲）");
    }
}
