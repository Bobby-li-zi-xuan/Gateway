package com.aigateway.decision.state;

import com.aigateway.core.exception.GatewayException;
import com.aigateway.decision.model.ModelState;
import com.aigateway.infra.config.GatewayProperties;
import com.aigateway.state.registry.ModelRegistry;
import org.springframework.stereotype.Component;

/**
 * 实例实时状态存储：EWMA 延迟 + 错误率 + 并发数，作为打分输入。
 *
 * 🖊 手敲 H5：本类为必手敲模块，按《版本2-详细实施计划》第 12 节实现。
 * 未完成前调用任何方法会直接报错。
 *
 * 需要实现的关键行为：
 * 1. 首次访问用配置的 latencyProfileMs 初始化（缺省 1000）；
 * 2. recordSuccess/recordFailure 用 ConcurrentHashMap.compute 原子更新 EWMA；
 * 3. inFlight 单独维护（高频计数），读取时合并进快照。
 */
@Component
public class ModelStateStore {

    private final GatewayProperties props;
    private final ModelRegistry registry;

    public ModelStateStore(GatewayProperties props, ModelRegistry registry) {
        this.props = props;
        this.registry = registry;
    }

    /** TODO H5：按《版本2-详细实施计划》第 12 节手敲实现 */
    public ModelState stateOf(String instanceId) {
        throw new GatewayException(500, "not_implemented",
                "TODO H5：ModelStateStore 未实现（按《版本2-详细实施计划》第 12 节手敲）");
    }

    /** TODO H5 */
    public void recordSuccess(String instanceId, long latencyMs) {
        throw new GatewayException(500, "not_implemented",
                "TODO H5：ModelStateStore 未实现（按《版本2-详细实施计划》第 12 节手敲）");
    }

    /** TODO H5 */
    public void recordFailure(String instanceId) {
        throw new GatewayException(500, "not_implemented",
                "TODO H5：ModelStateStore 未实现（按《版本2-详细实施计划》第 12 节手敲）");
    }

    /** TODO H5 */
    public void beginRequest(String instanceId) {
        throw new GatewayException(500, "not_implemented",
                "TODO H5：ModelStateStore 未实现（按《版本2-详细实施计划》第 12 节手敲）");
    }

    /** TODO H5 */
    public void endRequest(String instanceId) {
        throw new GatewayException(500, "not_implemented",
                "TODO H5：ModelStateStore 未实现（按《版本2-详细实施计划》第 12 节手敲）");
    }
}
