package com.aigateway.decision.state;

import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.decision.model.ModelState;
import com.aigateway.infra.config.GatewayProperties;
import com.aigateway.state.registry.ModelRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * 实例实时状态存储：EWMA 延迟 + 错误率 + 并发数，作为打分输入。
 *
 * 需要实现的关键行为：
 * 1. 首次访问用配置的 latencyProfileMs 初始化（缺省 1000）；
 * 2. recordSuccess/recordFailure 用 ConcurrentHashMap.compute 原子更新 EWMA；
 * 3. inFlight 单独维护（高频计数），读取时合并进快照。
 */
@Component
public class ModelStateStore {

    private final Map<String, ModelState> states = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final double latencyAlpha;  // EWMA 延迟系数（越大越跟手）
    private final double errorAlpha;    // EWMA 错误率系数
    private final ModelRegistry registry; // 读取候选的 latencyProfileMs 初始值

    public ModelStateStore(GatewayProperties props, ModelRegistry registry) {
        this.latencyAlpha = props.getDecision().getEwmaAlpha();
        this.errorAlpha = props.getDecision().getErrorRateAlpha();
        this.registry = registry;
    }

    /** 查询实例状态；首次访问用配置的 latencyProfileMs 初始化（缺省 1000） */
    public ModelState stateOf(String instanceId) {
        ModelState base = states.computeIfAbsent(instanceId, this::initialState);
        // inFlight 单独维护（高频计数），读取时合并进快照
        return new ModelState(base.instanceId(), base.ewmaLatencyMs(), base.errorRate(),
                inFlightOf(instanceId), base.lastUpdatedAt());
    }

    /**
     * 初始状态：延迟用配置的 latencyProfileMs（缺省 1000），错误率 0。
     * ⚠️ 只读 registry、不触碰 states map——CHM 明确禁止在 compute 回调里对
     * 同一 map 做任何其他更新（含 computeIfAbsent），否则运行期抛 IllegalStateException。
     */
    private ModelState initialState(String instanceId) {
        long initial = registry.findAll().stream()
                .filter(i -> i.instanceId().equals(instanceId))
                .findFirst().map(ModelInstance::latencyProfileMs).orElse(1000L);
        return new ModelState(instanceId, initial, 0.0, 0, System.currentTimeMillis());
    }

    /** 成功样本：EWMA 延迟向新样本滑动，错误率向 0 滑动 */
    public void recordSuccess(String instanceId, long latencyMs) {
        states.compute(instanceId, (id, old) -> {
            ModelState s = old != null ? old : initialState(id);
            double ewma = latencyAlpha * latencyMs + (1 - latencyAlpha) * s.ewmaLatencyMs();
            double err = (1 - errorAlpha) * s.errorRate();
            return new ModelState(id, ewma, err, s.inFlight(), System.currentTimeMillis());
        });
    }

    /** 失败样本：错误率 EWMA 向 1 滑动（延迟不更新，失败没有可用延迟样本） */
    public void recordFailure(String instanceId) {
        states.compute(instanceId, (id, old) -> {
            ModelState s = old != null ? old : initialState(id);
            double err = errorAlpha * 1.0 + (1 - errorAlpha) * s.errorRate();
            return new ModelState(id, s.ewmaLatencyMs(), err, s.inFlight(), System.currentTimeMillis());
        });
    }

    /** 请求开始：并发计数 +1（V3 负载因子输入） */
    public void beginRequest(String instanceId) {
        inFlight.computeIfAbsent(instanceId, k -> new AtomicInteger()).incrementAndGet();
    }

    /** 请求结束：并发计数 -1 */
    public void endRequest(String instanceId) {
        AtomicInteger counter = inFlight.get(instanceId);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }
    private int inFlightOf(String instanceId) {
        AtomicInteger counter = inFlight.get(instanceId);
        return counter == null ? 0 : counter.get();
    }
}
