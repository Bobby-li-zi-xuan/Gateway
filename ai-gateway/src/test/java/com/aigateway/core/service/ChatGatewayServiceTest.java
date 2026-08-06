package com.aigateway.core.service;

import com.aigateway.api.dto.ChatCompletion;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.execution.connector.OpenAIConnector;
import com.aigateway.observability.GatewayMetrics;
import com.aigateway.state.health.HealthChecker;
import com.aigateway.state.registry.ModelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网关主流程单测（计划第 16.1 节）：路由 + 失败切换 + 健康过滤。
 *
 * 覆盖：第一候选失败 → 第二候选成功；全部失败 → 502；健康过滤 → 503。
 */
class ChatGatewayServiceTest {

    private final ModelRegistry registry = mock(ModelRegistry.class);
    private final HealthChecker healthChecker = mock(HealthChecker.class);
    private final OpenAIConnector connector = mock(OpenAIConnector.class);
    private final GatewayMetrics metrics = mock(GatewayMetrics.class);
    private ChatGatewayService service;

    private static final ChatCompletion COMPLETION = new ChatCompletion(
            "chatcmpl-test", "chat.completion", 1L, "qwen-small",
            List.of(new ChatCompletion.Choice(0,
                    new ChatCompletion.Choice.Message("assistant", "ok"), "stop")),
            new ChatCompletion.Usage(1, 1, 2));

    @BeforeEach
    void setUp() {
        service = new ChatGatewayService(registry, healthChecker, connector, metrics);
        when(healthChecker.isHealthy(anyString())).thenReturn(true);
    }

    private ModelInstance instance(String id) {
        // V2 ModelInstance 新增价格/质量/延迟画像字段，测试用默认值
        return new ModelInstance(id, "qwen", "mock", "model", 1, null,
                0.0, 0.0, 0.5, 1000L);
    }

    private ChatRequest request() {
        return new ChatRequest("qwen", List.of(new ChatRequest.Message("user", "hi")),
                false, null, null);
    }

    @Test
    void firstCandidateFails_shouldFallbackToSecond() {
        // 候选 A、B 都健康；第一次调用必失败、第二次必成功（候选顺序随机，故不断言具体 instance）
        when(registry.findByAlias("qwen")).thenReturn(List.of(instance("mock-a:qwen-large"),
                instance("mock-b:qwen-small")));
        AtomicInteger calls = new AtomicInteger();
        when(connector.complete(any(), any())).thenAnswer(inv -> {
            if (calls.getAndIncrement() == 0) {
                throw new RuntimeException("boom");
            }
            return COMPLETION;
        });

        ChatCompletion result = service.complete(request(), "req-1");

        assertThat(result).isEqualTo(COMPLETION);
        verify(metrics).failure(anyString(), anyString()); // 一次失败
        verify(metrics).success(anyString(), anyString()); // 一次成功
    }

    @Test
    void allCandidatesFail_shouldReturn502() {
        when(registry.findByAlias("qwen")).thenReturn(List.of(instance("mock-a:qwen-large"),
                instance("mock-b:qwen-small")));
        when(connector.complete(any(), any())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> service.complete(request(), "req-1"))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> {
                    assertThat(((GatewayException) e).getStatus()).isEqualTo(502);
                    assertThat(((GatewayException) e).getType()).isEqualTo("upstream_failed");
                });
        verify(metrics, never()).success(anyString(), anyString());
    }

    @Test
    void allCandidatesFilteredByHealth_shouldReturn503() {
        // 候选存在但渠道全被健康检查剔除 → 503 no_available_model
        when(registry.findByAlias("qwen")).thenReturn(List.of(instance("mock-a:qwen-large"),
                instance("mock-b:qwen-small")));
        when(healthChecker.isHealthy(anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.complete(request(), "req-1"))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> {
                    assertThat(((GatewayException) e).getStatus()).isEqualTo(503);
                    assertThat(((GatewayException) e).getType()).isEqualTo("no_available_model");
                });
        verify(connector, never()).complete(any(), any());
    }

    @Test
    void unknownModel_shouldReturn503() {
        when(registry.findByAlias("unknown")).thenReturn(List.of());

        assertThatThrownBy(() -> service.complete(
                new ChatRequest("unknown", List.of(), false, null, null), "req-1"))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> {
                    assertThat(((GatewayException) e).getStatus()).isEqualTo(503);
                    assertThat(((GatewayException) e).getType()).isEqualTo("no_available_model");
                });
    }
}
