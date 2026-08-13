package com.aigateway.decision.state;

import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.decision.model.ModelState;
import com.aigateway.execution.model.CircuitState;
import com.aigateway.execution.model.FailureType;
import com.aigateway.infra.config.GatewayProperties;
import com.aigateway.state.registry.ModelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 状态闭环单测（V3 版，详细实施计划 18.1 H7）：
 * 事件入口 apply(StateEvent) 的聚合语义——EWMA 延迟、错误率衰减、慢调用累计、
 * 熔断状态快照、冷却按渠道广播、并发不丢更新。
 */
class ModelStateStoreTest {

    private final ModelRegistry registry = mock(ModelRegistry.class);
    private ModelStateStore store;

    private static final String INSTANCE = "mock-a:qwen-large";

    @BeforeEach
    void setUp() {
        ModelInstance instance = new ModelInstance(INSTANCE, "qwen", "mock-a", "qwen-large",
                1, null, 0.001, 0.002, 0.9, 1000L);
        when(registry.findAll()).thenReturn(List.of(instance));
        when(registry.findByChannelId("mock-a")).thenReturn(List.of(instance));
        store = new ModelStateStore(new GatewayProperties(), registry);
    }

    @Test
    void initialState_shouldUseConfiguredLatencyProfile() {
        ModelState state = store.stateOf(INSTANCE);
        assertThat(state.ewmaLatencyMs()).isEqualTo(1000.0); // 配置的 latencyProfileMs
        assertThat(state.errorRate()).isEqualTo(0.0);
        assertThat(state.circuitState()).isEqualTo(CircuitState.CLOSED); // V3 初始为关闭
        assertThat(state.cooling()).isFalse();
    }

    @Test
    void beginEndRequest_shouldTrackInFlight() {
        store.beginRequest(INSTANCE);
        store.beginRequest(INSTANCE);
        assertThat(store.stateOf(INSTANCE).inFlight()).isEqualTo(2);

        store.endRequest(INSTANCE);
        assertThat(store.stateOf(INSTANCE).inFlight()).isEqualTo(1);
    }

    /** 连续成功：EWMA 延迟向样本收敛（α=0.3），错误率衰减 */
    @Test
    void consecutiveSuccess_shouldConvergeLatencyAndDecayErrorRate() {
        store.apply(new StateEvent.Failure(INSTANCE, FailureType.HTTP_5XX));
        assertThat(store.stateOf(INSTANCE).errorRate()).isEqualTo(0.1); // α=0.1

        for (int i = 0; i < 20; i++) {
            store.apply(new StateEvent.Success(INSTANCE, 200));
        }
        ModelState state = store.stateOf(INSTANCE);
        assertThat(state.ewmaLatencyMs()).isLessThan(210.0);      // 向 200 收敛
        assertThat(state.errorRate()).isLessThan(0.02);           // 0.1×0.9^20 ≈ 0.012
        assertThat(state.totalCalls()).isEqualTo(21);             // 1 失败 + 20 成功
    }

    /** 失败：错误率上滑（α=0.1）、延迟不动；慢调用/熔断事件各自更新对应字段 */
    @Test
    void failure_shouldSlideErrorRateTowardsOneWithoutTouchingLatency() {
        store.apply(new StateEvent.Failure(INSTANCE, FailureType.HTTP_5XX));
        ModelState afterFailure = store.stateOf(INSTANCE);
        assertThat(afterFailure.errorRate()).isEqualTo(0.1);
        assertThat(afterFailure.ewmaLatencyMs()).isEqualTo(1000.0);  // 失败样本不带延迟

        store.apply(new StateEvent.SlowCall(INSTANCE, 12_000));
        assertThat(store.stateOf(INSTANCE).slowCalls()).isEqualTo(1);

        store.apply(new StateEvent.CircuitTransition(INSTANCE, CircuitState.CLOSED, CircuitState.OPEN));
        assertThat(store.stateOf(INSTANCE).circuitState()).isEqualTo(CircuitState.OPEN);
    }

    /** 冷却事件是渠道级：广播到该渠道全部实例；未知渠道不抛异常 */
    @Test
    void cooldownChange_shouldBroadcastToChannelInstances() {
        store.apply(new StateEvent.CooldownChange("mock-a", true, 30_000));
        assertThat(store.stateOf(INSTANCE).cooling()).isTrue();

        assertThatCode(() -> store.apply(new StateEvent.CooldownChange("unknown-channel", true, 30_000)))
                .doesNotThrowAnyException();
    }

    /** 并发更新不丢：50 次失败并发 apply 后错误率 ≈ 1 - 0.9^50（单写者纪律：测试直接调 apply） */
    @Test
    void concurrentUpdates_shouldNotLoseState() throws InterruptedException {
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    store.apply(new StateEvent.Failure(INSTANCE, FailureType.HTTP_5XX));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // 错误率 EWMA：1 - 0.9^50 ≈ 0.9948（50 次失败后收敛到接近 1）
        assertThat(store.stateOf(INSTANCE).errorRate()).isBetween(0.98, 1.0);
        assertThat(store.stateOf(INSTANCE).totalCalls()).isEqualTo(50);  // 不丢更新
    }
}
