package com.aigateway.infra.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求 ID 过滤器单测（计划第 16.1 节）。
 *
 * 覆盖：每个请求生成独立 requestId，写入请求属性并回传 X-Request-Id 响应头。
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    /** 走一次过滤器链，返回链路里看到的 requestId */
    private String runOnce(MockHttpServletResponse response) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, response, (req, resp) ->
                seen.set((String) req.getAttribute(RequestIdFilter.ATTR)));
        return seen.get();
    }

    @Test
    void twoRequests_shouldHaveIndependentIds() throws Exception {
        String id1 = runOnce(new MockHttpServletResponse());
        String id2 = runOnce(new MockHttpServletResponse());

        assertThat(id1).isNotBlank();
        assertThat(id1).isNotEqualTo(id2); // 两次请求的 requestId 必须不同
    }

    @Test
    void response_shouldCarryXRequestIdHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String id = runOnce(response);

        assertThat(response.getHeader("X-Request-Id")).isEqualTo(id);
    }
}
