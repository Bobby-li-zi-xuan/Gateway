package com.aigateway.core.service;

import com.aigateway.api.dto.ChatCompletion;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.service.ChatGatewayService.ChatResult;
import com.aigateway.execution.fallback.ExecutionChainResolver;
import com.aigateway.execution.fallback.FallbackChainExecutor;
import com.aigateway.execution.stream.StreamProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网关主流程单测（V3 接线版，详细实施计划 18.1 最后一行"构造器/签名更新"）：
 * ChatGatewayService 只做"链解析 + 交给执行器"，自身不含容错逻辑。
 *
 * 覆盖：非流式/流式都先解析候选链，再分别交给 FallbackChainExecutor（H5）/
 * StreamProxy（H6）；H5/H6 未手敲前用 mock，验证接线调用关系。
 */
class ChatGatewayServiceTest {

    private final ExecutionChainResolver chainResolver = mock(ExecutionChainResolver.class);
    private final FallbackChainExecutor fallbackExecutor = mock(FallbackChainExecutor.class);
    private final StreamProxy streamProxy = mock(StreamProxy.class);
    private ChatGatewayService service;

    private static final ChatCompletion COMPLETION = new ChatCompletion(
            "chatcmpl-test", "chat.completion", 1L, "qwen-small",
            List.of(new ChatCompletion.Choice(0,
                    new ChatCompletion.Choice.Message("assistant", "ok"), "stop")),
            new ChatCompletion.Usage(1, 1, 2));

    private static final Map<String, String> METADATA = Map.of("canary_group", "stable");

    @BeforeEach
    void setUp() {
        service = new ChatGatewayService(chainResolver, fallbackExecutor, streamProxy);
    }

    private ModelInstance instance(String id) {
        return new ModelInstance(id, "qwen", "mock", id.split(":")[1], 1, null,
                0.0, 0.0, 0.5, 1000L);
    }

    private ChatRequest request() {
        return new ChatRequest("qwen", List.of(new ChatRequest.Message("user", "hi")),
                false, null, null);
    }

    @Test
    void complete_shouldResolveChainAndDelegateToFallbackExecutor() {
        List<ModelInstance> chain = List.of(instance("mock-a:qwen-large"), instance("mock-b:qwen-small"));
        ModelInstance inst = instance("mock-a:qwen-large");
        when(chainResolver.resolve(any(), anyString(), any())).thenReturn(chain);
        when(fallbackExecutor.execute(eq(chain), any(), anyString(), any()))
                .thenReturn(new ChatResult(COMPLETION, inst));

        ChatResult result = service.complete(request(), "req-1", METADATA);

        assertThat(result.completion()).isEqualTo(COMPLETION);
        assertThat(result.instance()).isSameAs(inst);
        verify(chainResolver).resolve(request(), "req-1", METADATA);   // 链解析先执行
        verify(fallbackExecutor).execute(chain, request(), "req-1", METADATA); // 交给 H5
    }

    @Test
    void complete_shouldPropagateExecutionException() {
        // 执行器（H5 未手敲时为 TODO 异常）抛出的异常原样上抛，不做二次包装
        List<ModelInstance> chain = List.of(instance("mock-a:qwen-large"));
        when(chainResolver.resolve(any(), anyString(), any())).thenReturn(chain);
        when(fallbackExecutor.execute(any(), any(), anyString(), any()))
                .thenThrow(new UnsupportedOperationException("TODO H5"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.complete(request(), "req-1", METADATA))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void stream_shouldResolveChainAndDelegateToStreamProxy() {
        List<ModelInstance> chain = List.of(instance("mock-a:qwen-large"));
        when(chainResolver.resolve(any(), anyString(), any())).thenReturn(chain);
        // streamChain 是 void：用 doAnswer 模拟即可（验证调用关系）
        org.mockito.Mockito.doNothing().when(streamProxy).streamChain(
                eq(chain), any(), anyString(), any(), any());

        ChatRequest streamRequest = request().withStream(true);
        service.stream(streamRequest, "req-1", METADATA, chunk -> {
        });

        verify(chainResolver).resolve(streamRequest, "req-1", METADATA);
        verify(streamProxy).streamChain(eq(chain), eq(streamRequest), eq("req-1"),
                eq(METADATA), any());
    }
}
