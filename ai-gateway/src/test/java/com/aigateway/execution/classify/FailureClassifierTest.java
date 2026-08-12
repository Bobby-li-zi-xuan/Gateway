package com.aigateway.execution.classify;

import com.aigateway.execution.model.Failure;
import com.aigateway.execution.model.FailureType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败分类单测（脚手架，详细实施计划 18.1 前两行）：
 * 429 + Retry-After / 5xx / 4xx / 网络异常 / 超时的分类正确性。
 */
class FailureClassifierTest {

    private final FailureClassifier classifier = new FailureClassifier();
    private static final Set<Integer> RETRYABLE = Set.of(429, 500, 502, 503, 504);

    @Test
    void status429_shouldBeRetryableWithRetryAfter() {
        Failure f = classifier.classifyStatus(429, "rate limited", 2_000L, RETRYABLE);

        assertThat(f.type()).isEqualTo(FailureType.HTTP_429);
        assertThat(f.retryable()).isTrue();
        assertThat(f.retryAfterMs()).isEqualTo(2_000L);
    }

    @Test
    void status5xx_shouldBeRetryable() {
        for (int status : new int[]{500, 502, 503, 504}) {
            Failure f = classifier.classifyStatus(status, "boom", -1L, RETRYABLE);
            assertThat(f.type()).isEqualTo(FailureType.HTTP_5XX);
            assertThat(f.retryable()).isTrue();
            assertThat(f.upstreamStatus()).isEqualTo(status);
        }
    }

    @Test
    void status4xx_shouldBeNonRetryable() {
        Failure f = classifier.classifyStatus(400, "bad request", -1L, RETRYABLE);

        assertThat(f.type()).isEqualTo(FailureType.HTTP_OTHER);
        assertThat(f.retryable()).isFalse();   // 4xx 业务错误重试也没用
    }

    @Test
    void statusNotInRetryableList_shouldBeNonRetryable() {
        // 501 不在 retryableStatuses：即使 5xx 也不触发重试
        Failure f = classifier.classifyStatus(501, "not implemented", -1L, RETRYABLE);

        assertThat(f.type()).isEqualTo(FailureType.HTTP_OTHER);
        assertThat(f.retryable()).isFalse();
    }

    @Test
    void connectTimeout_shouldClassifyConnect() {
        Failure f = classifier.classifyException(new HttpConnectTimeoutException("connect timed out"));

        assertThat(f.type()).isEqualTo(FailureType.TIMEOUT_CONNECT);
        assertThat(f.retryable()).isTrue();
        assertThat(f.isTimeout()).isTrue();
    }

    @Test
    void requestTimeout_shouldClassifyRequest() {
        Failure f = classifier.classifyException(new HttpTimeoutException("request timed out"));

        assertThat(f.type()).isEqualTo(FailureType.TIMEOUT_REQUEST);
        assertThat(f.isTimeout()).isTrue();
    }

    @Test
    void connectionRefused_shouldClassifyConnection() {
        Failure f = classifier.classifyException(new ConnectException("refused"));

        assertThat(f.type()).isEqualTo(FailureType.CONNECTION);
        assertThat(f.retryable()).isTrue();   // 连接失败允许同实例重试一次
    }

    @Test
    void genericIo_shouldClassifyConnection() {
        Failure f = classifier.classifyException(new IOException("broken pipe"));

        assertThat(f.type()).isEqualTo(FailureType.CONNECTION);
    }
}
