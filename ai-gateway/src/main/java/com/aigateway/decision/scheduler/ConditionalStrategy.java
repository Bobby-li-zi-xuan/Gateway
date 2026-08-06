package com.aigateway.decision.scheduler;

import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.decision.model.Policy;
import com.aigateway.decision.model.RoutingDecision;
import com.aigateway.plugin.context.Signals;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 条件路由策略：规则按声明顺序求值，命中即覆盖候选集。
 *
 * 🖊 手敲 H4：规则求值逻辑（matches）按《版本2-详细实施计划》第 11 节手敲；
 * 未完成前调用 {@link #decide} 会直接报错。
 */
@Component
public class ConditionalStrategy {

    /** TODO H4：按《版本2-详细实施计划》第 11 节手敲实现 */
    public RoutingDecision decide(String requestId, String alias, Policy policy,
                                  List<ModelInstance> candidates,
                                  Signals signals) {
        throw new GatewayException(500, "not_implemented",
                "TODO H4：ConditionalStrategy 规则求值未实现（按《版本2-详细实施计划》第 11 节手敲）");
    }
}
