package com.aigateway.decision.state;

import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.decision.model.ModelState;
import com.aigateway.execution.model.CircuitState;
import com.aigateway.infra.config.GatewayProperties;
import com.aigateway.state.registry.ModelRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ⚠️ 手敲 H7（详细实施计划第 14 节 S12b）——apply() 需手敲，方法体当前抛 TODO 异常。
 *
 * 状态存储：EWMA 延迟 / 错误率（V2 已有算法）+ 熔断 / 冷却 / 慢调用快照（V3）。
 * 只被 StateEventPipeline 的单写者线程更新；请求线程只读 stateOf()。
 *
 * 脚手架已实现：stateOf / beginRequest / endRequest（V2 语义保留，签名不变）。
 *
 * 手敲要点（对照计划 14.3）：
 * - apply(StateEvent) 用 ConcurrentHashMap.compute 原子应用五种事件（Success/Failure/
 *   SlowCall/CircuitTransition/CooldownChange），EWMA 公式 new = α×sample + (1−α)×old；
 * - CooldownChange 是渠道级事件：按渠道广播到旗下全部实例（registry.findByChannelId），
 *   compute 回调内禁止触碰同一 map 的其它键，实例列表必须在 compute 外取；
 * - ⚠️ V2 的 recordSuccess/recordFailure 已删除，统一改为事件入口（单写者纪律）。
 */
@Component
public class ModelStateStore {

    private final Map<String, ModelState> states = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final ModelRegistry registry;   // 冷却事件是渠道级，需查渠道下全部实例
    private final double latencyAlpha;  // EWMA 延迟系数（V2 decision.ewmaAlpha，缺省 0.3）
    private final double errorAlpha;    // 错误率系数（缺省 0.1）

    public ModelStateStore(GatewayProperties props, ModelRegistry registry) {
        // 参数从 V2 的 decision 配置段读取（缺省 0.3 / 0.1）
        this.latencyAlpha = props.getDecision().getEwmaAlpha();
        this.errorAlpha = props.getDecision().getErrorRateAlpha();
        this.registry = registry;
    }

    /** 读最近快照（决策层用，零阻塞） */
    public ModelState stateOf(String instanceId) {
        ModelState base = states.computeIfAbsent(instanceId, this::initialState);
        return new ModelState(base.instanceId(), base.ewmaLatencyMs(), base.errorRate(),
                inFlightOf(instanceId), base.lastUpdatedAt(),
                base.circuitState(), base.slowCalls(), base.totalCalls(), base.cooling());
    }

    /** 初始状态：延迟用实例的 latencyProfileMs 配置（缺省 1000），其余为中性值 */
    private ModelState initialState(String instanceId) {
        long initial = registry.findAll().stream()
                .filter(i -> i.instanceId().equals(instanceId))
                .findFirst().map(ModelInstance::latencyProfileMs).orElse(1000L);
        return new ModelState(instanceId, initial, 0.0, 0,
                System.currentTimeMillis(), CircuitState.CLOSED, 0, 0, false);
    }

    /** 请求开始：并发计数 +1（执行器调用，不是事件——高频且只对执行有意义） */
    public void beginRequest(String instanceId) {
        inFlight.computeIfAbsent(instanceId, k -> new AtomicInteger()).incrementAndGet();
    }

    /** 请求结束：并发计数 -1 */
    public void endRequest(String instanceId) {
        AtomicInteger counter = inFlight.get(instanceId);
        if (counter != null) counter.decrementAndGet();
    }

    /** 单写者入口：把事件应用到状态（ConcurrentHashMap.compute 保证原子性） */
    public void apply(StateEvent event) {
       switch (event) {
            case StateEvent.Success s -> states.compute(s.instanceId(), (id, old) -> {
                ModelState st = old != null ? old : initialState(id);
                double ewma = latencyAlpha * s.latencyMs() + (1 - latencyAlpha) * st.ewmaLatencyMs();
                double err = (1 - errorAlpha) * st.errorRate();
                return withBase(st, ewma, err, st.circuitState(), st.slowCalls(),
                        st.totalCalls() + 1, st.cooling());
            });
            case StateEvent.Failure f -> states.compute(f.instanceId(), (id, old) -> {
                ModelState st = old != null ? old : initialState(id);
                double err = errorAlpha * 1.0 + (1 - errorAlpha) * st.errorRate();
                return withBase(st, st.ewmaLatencyMs(), err, st.circuitState(),
                        st.slowCalls(), st.totalCalls() + 1, st.cooling());
            });
            case StateEvent.SlowCall sc -> states.compute(sc.instanceId(), (id, old) -> {
                ModelState st = old != null ? old : initialState(id);
                return withBase(st, st.ewmaLatencyMs(), st.errorRate(), st.circuitState(),
                        st.slowCalls() + 1, st.totalCalls(), st.cooling());
            });
            case StateEvent.CircuitTransition ct -> states.compute(ct.instanceId(), (id, old) -> {
                ModelState st = old != null ? old : initialState(id);
                return withBase(st, st.ewmaLatencyMs(), st.errorRate(), ct.to(),
                        st.slowCalls(), st.totalCalls(), st.cooling());
            });
            case StateEvent.CooldownChange cc -> {
                // 冷却按渠道，状态按实例展示：广播到该渠道下所有实例。
                // 边界处理：先在 compute 外取实例列表（compute 回调内禁止触碰 map 其它键）；
                // 注册表中暂无该渠道 → 空列表，循环不执行（下游决策读到旧冷却态，可接受）。
                List<ModelInstance> instances = registry.findByChannelId(cc.channelId());
                for (ModelInstance inst : instances) {
                    states.compute(inst.instanceId(), (id, old) -> {
                        ModelState st = old != null ? old : initialState(id);
                        return withBase(st, st.ewmaLatencyMs(), st.errorRate(),
                                st.circuitState(), st.slowCalls(), st.totalCalls(), cc.cooling());
                    });
                }
            }
        }
    }

    private ModelState withBase(ModelState st, double ewma, double err, CircuitState circuit,
                                long slowCalls, long totalCalls, boolean cooling) {
        return new ModelState(st.instanceId(), ewma, err, st.inFlight(),
                System.currentTimeMillis(), circuit, slowCalls, totalCalls, cooling);
    }

    private int inFlightOf(String instanceId) {
        AtomicInteger counter = inFlight.get(instanceId);
        return counter == null ? 0 : counter.get();
    }
}
