package com.aigateway.execution.circuit;

import com.aigateway.execution.model.CircuitBreakerConfig;
import com.aigateway.execution.model.CircuitState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H3 单测（骨架 + OPEN 迟到记录用例）：
 * 打开态迟到记录（allowRequest 放行后、打开前发出的存量请求返回时才 record）必须被忽略——
 * 不进窗口、不刷新打开期、不产生转换事件。
 *
 * 待写用例（对照计划 18.1 表格）：
 * 1. insufficientSamples_shouldNotTrip：minimumRequests 前一直放行；
 * 2. failureRateExceeded_shouldOpen：失败率超阈值 → allowRequest()=false（OPEN）；
 * 3. halfOpenProbeSuccess_shouldClose：打开期到期 → HALF_OPEN 放行探测 → 探测成功 → CLOSED
 *    且窗口清零；
 * 4. halfOpenProbeFailure_shouldReopen：探测失败 → 回到 OPEN 且 allowRequest=false；
 * 5. windowSlide_shouldRecoverFailureRate：旧失败被挤出窗口后失败率回落。
 *
 * 依赖说明：new CircuitBreaker(instanceId, config) 纯构造，无 Spring 依赖。
 */
class CircuitBreakerTest {

    private static final CircuitBreakerConfig CONFIG = new CircuitBreakerConfig(
            100, 5, 0.5, 10_000, 0.5, 30_000, 1);

    /** OPEN 态迟到记录：状态不变、打开期不刷新、返回无转换（注册表不会重复发事件） */
    @Test
    void lateRecordInOpen_shouldIgnore() throws Exception {
        CircuitBreaker cb = new CircuitBreaker("mock-a:qwen", CONFIG);
        // 5 次失败触发 OPEN
        for (int i = 0; i < 5; i++) {
            cb.record(false, 100, 10_000L);
        }
        assertThat(cb.state()).isEqualTo(CircuitState.OPEN);

        Field openUntil = CircuitBreaker.class.getDeclaredField("openUntilMs");
        openUntil.setAccessible(true);
        long openUntilBefore = openUntil.getLong(cb);

        // 模拟"打开前已放行的存量请求"迟到 record
        CircuitBreaker.StateTransition t = cb.record(false, 100, 10_000L);

        assertThat(cb.state()).isEqualTo(CircuitState.OPEN);       // 状态不变
        assertThat(openUntil.getLong(cb)).isEqualTo(openUntilBefore); // 打开期未被刷新延长
        assertThat(t.from()).isEqualTo(CircuitState.OPEN);         // 无转换 → 无事件
        assertThat(t.to()).isEqualTo(CircuitState.OPEN);
    }
}
