package com.aigateway.decision.scorer;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.decision.model.ScoringDetail;
import com.aigateway.decision.state.ModelStateStore;
import com.aigateway.infra.config.GatewayProperties;
import com.aigateway.plugin.context.Signals;
import com.aigateway.state.health.HealthChecker;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 多目标打分器：延迟 / 成本 / 质量 / 健康四因子，归一化后加权求和。
 *
 * 🖊 手敲 H3：本类为必手敲模块，按《版本2-详细实施计划》第 10 节实现。
 * 未完成前调用 {@link #score} 会直接报错。
 *
 * 需要实现的关键行为：
 * 1. min-max 归一化（max==min 给满分，避免除零）；
 * 2. 动态调权（读 signals：task_complexity / budget_remaining_ratio），调整后重新归一化；
 * 3. 每个候选输出 raw / normalized / weights / finalScore 完整明细（可解释路由）。
 */
@Component
public class Scorer {

    private final ModelStateStore stateStore;    // EWMA 延迟 / 错误率（H5 手敲）
    private final HealthChecker healthChecker;   // 健康快照
    private final GatewayProperties props;       // defaultOutputTokens 等

    public Scorer(ModelStateStore stateStore, HealthChecker healthChecker,
                  GatewayProperties props) {
        this.stateStore = stateStore;
        this.healthChecker = healthChecker;
        this.props = props;
    }

    /** TODO H3：按《版本2-详细实施计划》第 10 节手敲实现 */
    public List<ScoringDetail> score(List<ModelInstance> candidates,
                                     Map<String, Double> weights,
                                     Signals signals,
                                     ChatRequest request) {
        throw new GatewayException(500, "not_implemented",
                "TODO H3：Scorer 未实现（按《版本2-详细实施计划》第 10 节手敲）");
    }
}
