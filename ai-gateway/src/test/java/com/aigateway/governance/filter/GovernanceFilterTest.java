package com.aigateway.governance.filter;

import com.aigateway.api.controller.ChatCompletionController;
import com.aigateway.api.dto.ChatCompletion;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.service.ChatGatewayService;
import com.aigateway.core.service.ChatGatewayService.ChatResult;
import com.aigateway.governance.config.GovernanceProperties;
import com.aigateway.governance.model.ApiToken;
import com.aigateway.governance.model.TokenStatus;
import com.aigateway.governance.ratelimit.RateLimiter;
import com.aigateway.governance.token.TokenManager;
import com.aigateway.observability.GatewayMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 脚手架测试：GovernanceFilter 鉴权 / 限流 / 放行（MockMvc，对照 20.1）。
 * 手敲依赖（TokenManager / RateLimiter）用 mock，测试本身不依赖 H1/H2 实现。
 */
class GovernanceFilterTest {

    private MockMvc mockMvc;
    private ChatGatewayService gatewayService;
    private TokenManager tokenManager;
    private RateLimiter rateLimiter;

    private static final String CHAT_BODY = "{\"model\":\"qwen\","
            + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

    @BeforeEach
    void setUp() {
        gatewayService = mock(ChatGatewayService.class);
        tokenManager = mock(TokenManager.class);
        rateLimiter = mock(RateLimiter.class);
        GatewayMetrics metrics = mock(GatewayMetrics.class);

        GovernanceProperties props = new GovernanceProperties();
        props.setEnabled(true);
        props.getRateLimit().setDefaultQps(5);
        props.getRateLimit().setDefaultRpm(1000);

        ChatCompletionController controller = new ChatCompletionController(gatewayService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new GovernanceFilter(props, tokenManager, rateLimiter, metrics))
                .build();
    }

    @Test
    void missingAuthorization_returns401() throws Exception {
        // resolve 默认 empty + validate 默认 MISSING → 401 invalid_token
        when(tokenManager.validate(any(), anyString())).thenReturn(TokenStatus.MISSING);

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType("application/json").content(CHAT_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("invalid_token"));
    }

    @Test
    void validToken_passesThroughToGateway() throws Exception {
        ApiToken token = new ApiToken("agw_00000001", "t", "hash", -1, "COST", "", "", -1, true, 0);
        when(tokenManager.resolve(any(), anyString())).thenReturn(Optional.of(token));
        when(rateLimiter.tryAcquire(any(), anyLong())).thenReturn(true);

        ModelInstance inst = new ModelInstance("mock-a:qwen", "qwen", "mock-a", "qwen",
                1, null, 0.001, 0.002, 0.5, 1000);
        ChatCompletion completion = new ChatCompletion("id", "chat.completion", 0, "qwen",
                List.of(new ChatCompletion.Choice(0,
                        new ChatCompletion.Choice.Message("assistant", "hi"), "stop")), null);
        when(gatewayService.complete(any(), any(), any()))
                .thenReturn(new ChatResult(completion, inst));

        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer agw_whatever")
                        .contentType("application/json").content(CHAT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").value("hi"));
    }

    @Test
    void rateLimited_returns429WithRetryAfter() throws Exception {
        ApiToken token = new ApiToken("agw_00000001", "t", "hash", -1, "COST", "", "", -1, true, 0);
        when(tokenManager.resolve(any(), anyString())).thenReturn(Optional.of(token));
        when(rateLimiter.tryAcquire(any(), anyLong())).thenReturn(false);   // 限流命中

        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer agw_whatever")
                        .contentType("application/json").content(CHAT_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.type").value("rate_limit_exceeded"));
    }
}
