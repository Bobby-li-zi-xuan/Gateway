package com.aigateway.decision.state;

import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.decision.model.ModelState;
import com.aigateway.execution.model.CircuitState;
import com.aigateway.infra.config.GatewayProperties;
import com.aigateway.state.registry.ModelRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H7 单测（详细实施计划 18.1 H7 行）：
 * offer 入队 → flush() 排空 → ModelStateStore.apply 生效（flush 是唯一应用入口，
 * 测试里手动调用做确定性断言）。
 *
 * 依赖说明：ModelStateStore 用真实实例（GatewayProperties 默认参数 + mock ModelRegistry）；
 * 直接 new 不触发 @PostConstruct 的定时器，由测试手动 flush()。
 */
class StateEventPipelineTest {

    private static final String INSTANCE = "mock-a:qwen-large";

    private ModelRegistry newRegistry() {
        ModelRegistry registry = mock(ModelRegistry.class);
        ModelInstance instance = new ModelInstance(INSTANCE, "qwen", "mock-a", "qwen-large",
                1, null, 0.001, 0.002, 0.9, 1000L);
        when(registry.findAll()).thenReturn(List.of(instance));
        when(registry.findByChannelId("mock-a")).thenReturn(List.of(instance));
        return registry;
    }

    @Test
    void offerThenFlush_shouldApplyEvents() {
        GatewayProperties props = new GatewayProperties();  // ewmaAlpha=0.3
        ModelStateStore store = new ModelStateStore(props, newRegistry());
        StateEventPipeline pipeline = new StateEventPipeline(store, props,
                Executors.newScheduledThreadPool(1));

        pipeline.offer(new StateEvent.Success(INSTANCE, 200));
        pipeline.offer(new StateEvent.CircuitTransition(INSTANCE, CircuitState.CLOSED, CircuitState.OPEN));
        pipeline.flush();

        ModelState state = store.stateOf(INSTANCE);
        // EWMA：0.3×200 + 0.7×初始1000 = 760
        assertThat(state.ewmaLatencyMs()).isEqualTo(760.0);
        assertThat(state.totalCalls()).isEqualTo(1);
        assertThat(state.circuitState()).isEqualTo(CircuitState.OPEN);  // 熔断状态决策层可见
    }

    @Test
    void flushEmpty_shouldBeNoop() {
        GatewayProperties props = new GatewayProperties();
        ModelStateStore store = new ModelStateStore(props, newRegistry());
        StateEventPipeline pipeline = new StateEventPipeline(store, props,
                Executors.newScheduledThreadPool(1));

        pipeline.flush();  // 空队列不抛异常、状态不变

        ModelState state = store.stateOf(INSTANCE);
        assertThat(state.totalCalls()).isZero();
        assertThat(state.errorRate()).isZero();
    }
}
