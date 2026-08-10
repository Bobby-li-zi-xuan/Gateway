package com.aigateway.decision.state;

import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.decision.model.ModelState;
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
 * 状态闭环单测（计划 20.1，H5）：
 * EWMA 收敛 / 并发更新不丢（最终状态合法）。
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
        store = new ModelStateStore(new GatewayProperties(), registry);
    }

    @Test
    void initialState_shouldUseConfiguredLatencyProfile() {
        ModelState state = store.stateOf(INSTANCE);
        assertThat(state.ewmaLatencyMs()).isEqualTo(1000.0); // 配置的 latencyProfileMs
        assertThat(state.errorRate()).isEqualTo(0.0);
    }

    @Test
    void consecutiveSuccess_shouldConvergeLatencyAndDecayErrorRate() {
        // 连续成功样本后 EWMA 延迟向样本值收敛（α=0.3），错误率向 0 衰减
        store.recordFailure(INSTANCE); // 先制造一个错误样本
        double errorAfterFailure = store.stateOf(INSTANCE).errorRate();
        assertThat(errorAfterFailure).isGreaterThan(0.0);

        double latency = store.stateOf(INSTANCE).ewmaLatencyMs();
        for (int i = 0; i < 20; i++) {
            store.recordSuccess(INSTANCE, 200);
            double next = store.stateOf(INSTANCE).ewmaLatencyMs();
            assertThat(next).isLessThan(latency); // 单调向 200 收敛
            latency = next;
        }
        assertThat(store.stateOf(INSTANCE).ewmaLatencyMs()).isCloseTo(200.0,
                org.assertj.core.data.Offset.offset(1.0));
        // 错误率：0.1 × 0.9^20 ≈ 0.0122，收敛到接近 0（宽松区间避免浮点敏感）
        assertThat(store.stateOf(INSTANCE).errorRate()).isLessThan(0.02);
    }

    @Test
    void failure_shouldSlideErrorRateTowardsOneWithoutTouchingLatency() {
        double before = store.stateOf(INSTANCE).ewmaLatencyMs();

        store.recordFailure(INSTANCE);

        ModelState state = store.stateOf(INSTANCE);
        assertThat(state.errorRate()).isEqualTo(0.1); // α=0.1：0.1×1 + 0.9×0
        assertThat(state.ewmaLatencyMs()).isEqualTo(before); // 失败没有可用延迟样本
    }

    @Test
    void concurrentUpdates_shouldNotLoseState() throws InterruptedException {
        // 多线程并发 recordFailure：错误率按 EWMA 公式每次乘 0.9 再 +0.1，
        // 最终理论值 = 1 - 0.9^50；若丢更新，最终值会显著偏低
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
                    store.recordFailure(INSTANCE);
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

        double expected = 1.0 - Math.pow(0.9, threads); // 0.995 附近
        assertThat(store.stateOf(INSTANCE).errorRate())
                .isCloseTo(expected, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void beginEndRequest_shouldTrackInFlight() {
        store.beginRequest(INSTANCE);
        store.beginRequest(INSTANCE);
        assertThat(store.stateOf(INSTANCE).inFlight()).isEqualTo(2);

        store.endRequest(INSTANCE);
        assertThat(store.stateOf(INSTANCE).inFlight()).isEqualTo(1);
    }
}
