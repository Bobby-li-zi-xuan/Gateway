package com.aigateway.decision.state;

import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.decision.model.ModelState;
import com.aigateway.execution.model.CircuitState;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 状态闭环单测（V3 版，详细实施计划 18.1 H7）：
 * 脚手架部分（stateOf / beginRequest / endRequest）已可用；
 * apply(StateEvent) 相关用例需在 H7 手敲完成后启用（当前标注 TODO H7）。
 *
 * V2 → V3 变化：recordSuccess/recordFailure 已删除，统一改为事件入口
 * StateEventPipeline.flush() → ModelStateStore.apply(event)。
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

    // ══ TODO H7（手敲完成后启用）══
    // 以下用例的调用入口已改为事件管道，需 H7 实现 apply 后运行：

    @Test
    void consecutiveSuccess_shouldConvergeLatencyAndDecayErrorRate() {
        // TODO H7: store.apply(new StateEvent.Failure(INSTANCE, FailureType.HTTP_5XX));
        //         → 错误率 > 0
        // TODO H7: 连续 20 次 store.apply(new StateEvent.Success(INSTANCE, 200))
        //         → EWMA 延迟向 200 收敛（α=0.3）、错误率 < 0.02
    }

    @Test
    void failure_shouldSlideErrorRateTowardsOneWithoutTouchingLatency() {
        // TODO H7: apply(Failure) 后错误率 = 0.1（α=0.1）、延迟不变
        // TODO H7: apply(SlowCall) 后 slowCalls 累计、apply(CircuitTransition) 后 circuitState 更新
    }

    @Test
    void cooldownChange_shouldBroadcastToChannelInstances() {
        // TODO H7: apply(new StateEvent.CooldownChange("mock-a", true, 30_000))
        //         → 该渠道全部实例 cooling() == true；未知渠道不抛异常
    }

    @Test
    void concurrentUpdates_shouldNotLoseState() throws InterruptedException {
        // TODO H7: 多线程并发 offer(Failure) + flush() 后错误率接近 1 - 0.9^50
        // （单写者纪律：测试里手动调用 flush() 代替定时器）
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
                    // TODO H7: store.apply(new StateEvent.Failure(INSTANCE, FailureType.HTTP_5XX));
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

        // TODO H7: 断言错误率 ≈ 1 - 0.9^50（若 apply 未实现，此断言不会执行）
    }
}
