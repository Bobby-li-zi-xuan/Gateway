package com.aigateway.execution.connector;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.api.dto.ChatCompletion;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.Channel;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.execution.classify.FailureClassifier;
import com.aigateway.execution.model.ExecutionPolicies;
import com.aigateway.execution.model.Failure;
import com.aigateway.execution.model.FailureType;
import com.aigateway.execution.model.StreamSession;
import com.aigateway.execution.model.UpstreamCallException;
import com.aigateway.execution.stream.ClientDisconnectedException;
import com.aigateway.execution.timeout.TimeoutGuard;
import com.aigateway.infra.config.SecretResolver;
import com.aigateway.infra.http.HttpClientFactory;
import com.aigateway.observability.GatewayMetrics;
import com.aigateway.state.registry.ModelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * OpenAI 兼容上游连接器（阻塞式，JDK HttpClient）——V3 改造版（脚手架）。
 *
 * 改造点（对照详细实施计划 9.4）：
 * 1. 超时参数化：连接超时（connectMs，按渠道缓存客户端）与完整响应超时（requestMs）来自
 *    {@link ExecutionPolicies}，不再写死 30s；
 * 2. 失败分类：所有失败统一转 {@link UpstreamCallException}（结构化 Failure），
 *    由 {@link FailureClassifier} 判定类型与是否可重试；超时分类处打 metrics.timeout 指标；
 * 3. 流式返回 {@link StreamSession}：客户端断开 / 超时都能显式取消上游连接
 *    （JDK 语义：未消费完的响应体 close() 即取消底层交换）；
 * 4. 首字节 / 空闲超时由 {@link TimeoutGuard}（H2）定时器控制，到点关闭 body 流；
 *    定时器与取消用标记区分"超时关闭 / 主动取消 / 真实失败"，避免把取消误报为上游失败。
 */
@Component
public class OpenAIConnector {

    private final HttpClientFactory httpClientFactory; // 按 connectMs 缓存客户端
    private final ModelRegistry registry;      // 查渠道 baseUrl / 鉴权配置
    private final SecretResolver secretResolver; // env: 密钥引用解析
    private final ObjectMapper objectMapper;   // 请求序列化 + 响应/SSE 反序列化
    private final FailureClassifier classifier; // 异常/状态码 → 结构化 Failure
    private final GatewayMetrics metrics;      // 超时指标打点
    private final ScheduledExecutorService scheduler; // TimeoutGuard 定时器（全局 gatewayScheduler）

    public OpenAIConnector(HttpClientFactory httpClientFactory,
                           ModelRegistry registry,
                           SecretResolver secretResolver,
                           ObjectMapper objectMapper,
                           FailureClassifier classifier,
                           GatewayMetrics metrics,
                           ScheduledExecutorService scheduler) {
        this.httpClientFactory = httpClientFactory;
        this.registry = registry;
        this.secretResolver = secretResolver;
        this.objectMapper = objectMapper;
        this.classifier = classifier;
        this.metrics = metrics;
        this.scheduler = scheduler;
    }

    /**
     * 非流式调用：requestMs 超时 + 连接超时按渠道配置；失败统一转 UpstreamCallException。
     * 429 时解析 Retry-After 头（秒 → 毫秒）带给失败样本，供 RetryExecutor（H1）决定等待时长。
     */
    public ChatCompletion complete(ModelInstance instance, ChatRequest request, ExecutionPolicies policies) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(channelBaseUrl(instance) + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(policies.timeout().requestMs()))
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(request)));
        applyAuth(builder, instance);
        try {
            HttpResponse<String> response = clientFor(policies).send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw upstreamFailure(instance, response.statusCode(), response.body(),
                        response.headers(), policies);
            }
            return objectMapper.readValue(response.body(), ChatCompletion.class);
        } catch (UpstreamCallException e) {
            throw e; // 已结构化的失败直接上抛，不做二次包装
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw classified(instance, classifier.classifyException(e), e);
        }
    }

    /**
     * 流式调用：返回 {@link StreamSession} 句柄，调用方（StreamProxy H6）可 cancel() 取消上游。
     *
     * 超时治理（详细实施计划 9.4）：
     * - 首字节超时：send 成功后注册一次性定时器，到点关闭 body 流（未消费完 → 取消上游交换）；
     * - 空闲超时：每个有效 chunk 重置定时器；
     * - 定时器到点后阻塞中的 readLine() 抛 IOException，读循环按"定时器触发标记"分类为
     *   TIMEOUT_FIRST_BYTE / TIMEOUT_IDLE。
     *
     * ⚠️ H2 未手敲前，这里 new TimeoutGuard(...) 后的定时器调用会抛 TODO 异常——计划预期的
     * "未完成前调用对应功能直接报错"（先完成 H2 再验证流式）。
     */
    public StreamSession stream(ModelInstance instance, ChatRequest request, ExecutionPolicies policies,
                                Consumer<ChatChunk> consumer) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(channelBaseUrl(instance) + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMillis(policies.timeout().requestMs()))
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(request.withStream(true))));
        applyAuth(builder, instance);

        HttpResponse<InputStream> response;
        try {
            response = clientFor(policies).send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw classified(instance, classifier.classifyException(e), e);
        }
        if (response.statusCode() / 100 != 2) {
            String body;
            try {
                body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                body = "<无法读取错误体>";
            }
            throw upstreamFailure(instance, response.statusCode(), body, response.headers(), policies);
        }
        return readSse(instance, policies, response.body(), consumer);
    }

    /** 流式读循环：逐行解析转发，并用定时器 + 标记区分"超时 / 取消 / 真实失败" */
    private StreamSession readSse(ModelInstance instance, ExecutionPolicies policies,
                                  InputStream body, Consumer<ChatChunk> consumer) {
        HttpStreamSession session = new HttpStreamSession(body);
        // 首字节定时器：响应头已到但首个 data 行迟迟不来 → 超时关闭
        TimeoutGuard guard = new TimeoutGuard(policies.totalMs(), scheduler);
        guard.scheduleOnce(policies.timeout().firstByteMs(), () -> {
            if (!session.firstByteArrived()) {   // 竞态防护：首字节已到则忽略
                session.markTimedOut(PHASE_FIRST_BYTE);
                closeQuietly(body);
            }
        });
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(body, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                ChatChunk chunk = parseSseLine(line);
                if (chunk == null) continue;    // [DONE] / 非 data 行：忽略
                // 收到有效 chunk：取消旧定时器（首字节 / 上一个空闲），重置空闲定时器
                session.markFirstByte();
                guard.cancelTimers();
                guard.scheduleOnce(policies.timeout().idleMs(), () -> {
                    session.markTimedOut(PHASE_IDLE);
                    closeQuietly(body);
                });
                consumer.accept(chunk);
            }
            guard.cancelTimers();
            return session;   // 正常结束（读到 EOF / [DONE] 后 readLine 返回 null）
        } catch (ClientDisconnectedException e) {
            // 客户端断开：取消上游连接后原样放行（不包装），由 StreamProxy（H6）走"取消"路径
            session.cancel();
            throw e;
        } catch (IOException e) {
            // 区分"被取消 / 超时关闭"与"真实失败"（风险第 2 条）：
            // 定时器关闭流、客户端断开都会让 readLine() 抛 IOException，不能一律记上游失败
            if (session.timedOutPhase() == PHASE_FIRST_BYTE) {
                throw classified(instance,
                        new Failure(FailureType.TIMEOUT_FIRST_BYTE, "流式首字节超时", -1, true, -1), e);
            }
            if (session.timedOutPhase() == PHASE_IDLE) {
                throw classified(instance,
                        new Failure(FailureType.TIMEOUT_IDLE, "流式空闲超时", -1, false, -1), e);
            }
            if (session.isCancelled()) {
                // 主动取消（客户端断开时连接器已 cancel）：不算失败，不污染熔断/冷却统计
                throw new UpstreamCallException(
                        new Failure(FailureType.CANCELLED, "上游连接已被取消", -1, false, -1), e);
            }
            throw classified(instance, classifier.classifyException(e), e);
        }
    }

    /** 定时器触发阶段标记（与 HttpStreamSession.timedOutPhase 对应） */
    private static final int PHASE_FIRST_BYTE = 1;
    private static final int PHASE_IDLE = 2;

    /**
     * SSE 行解析：JSON 解析失败抛结构化 BAD_SSE（首字节前可降级，首字节后由 StreamProxy 拦截）。
     */
    private ChatChunk parseSseLine(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("data:")) {
            return null; // 忽略注释 / 心跳等非数据行
        }
        String payload = trimmed.substring(5).trim(); // 去掉 "data:" 前缀
        if ("[DONE]".equals(payload)) {
            return null; // 流结束标记：调用方据此结束循环
        }
        try {
            return objectMapper.readValue(payload, ChatChunk.class);
        } catch (IOException e) {
            throw new UpstreamCallException(new Failure(FailureType.BAD_SSE,
                    "上游返回了无法解析的 SSE 数据: " + payload, -1, true, -1), e);
        }
    }

    /** 非 2xx → 结构化失败（429 带 Retry-After 头）；Retry-After 秒数转毫秒带给失败样本 */
    private UpstreamCallException upstreamFailure(ModelInstance instance, int status, String body,
                                                  HttpHeaders headers, ExecutionPolicies policies) {
        Failure f = classifier.classifyStatus(status, "上游返回 " + status + ": " + body,
                retryAfterMs(headers), policies.retry().retryableStatuses());
        return new UpstreamCallException(f);
    }

    /** 结构化失败上抛，超时类型顺带打 metrics.timeout（stage=connect/request/first_byte/idle） */
    private UpstreamCallException classified(ModelInstance instance, Failure f, Throwable cause) {
        if (f.isTimeout()) {
            String stage = switch (f.type()) {
                case TIMEOUT_CONNECT -> "connect";
                case TIMEOUT_REQUEST -> "request";
                case TIMEOUT_FIRST_BYTE -> "first_byte";
                case TIMEOUT_IDLE -> "idle";
                default -> null;
            };
            if (stage != null) metrics.timeout(instance.instanceId(), stage);
        }
        return new UpstreamCallException(f, cause);
    }

    /** 解析 Retry-After（秒 → 毫秒）；缺失/非法返回 -1（由 RetryExecutor 按退避策略处理） */
    private long retryAfterMs(HttpHeaders headers) {
        return headers.firstValue("Retry-After")
                .map(v -> {
                    try {
                        long seconds = Long.parseLong(v.trim());
                        return seconds >= 0 ? seconds * 1000L : -1L;
                    } catch (NumberFormatException e) {
                        return -1L;
                    }
                })
                .orElse(-1L);
    }

    /** 按策略的连接超时取客户端（同一 connectMs 共享连接池） */
    private HttpClient clientFor(ExecutionPolicies policies) {
        return httpClientFactory.clientFor(policies.timeout().connectMs());
    }

    /** 按渠道配置附加鉴权头（V1 语义保留） */
    private void applyAuth(HttpRequest.Builder builder, ModelInstance instance) {
        registry.findChannel(instance.channelId())
                .filter(Channel::needAuth)
                .ifPresent(ch -> secretResolver.resolve(ch.credentialsRef())
                        .ifPresent(token -> builder.header("Authorization", "Bearer " + token)));
    }

    /** 把对象序列化为 JSON；序列化失败属于内部错误（500） */
    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new GatewayException(500, "internal_error", "请求序列化失败", e);
        }
    }

    /** 从注册中心取渠道 baseUrl；启动校验后理论上渠道必然存在，这里仍做兜底 */
    private String channelBaseUrl(ModelInstance instance) {
        return registry.findChannel(instance.channelId())
                .map(Channel::baseUrl)
                .orElseThrow(() -> new GatewayException(500, "invalid_config",
                        "渠道不存在: " + instance.channelId()));
    }

    private static void closeQuietly(InputStream body) {
        try {
            if (body != null) body.close();
        } catch (IOException ignore) {
            // 关闭失败无副作用（连接已被底层处理）
        }
    }

    /** 流式会话句柄实现：cancel() 关闭响应体 = 取消上游交换（幂等） */
    private static final class HttpStreamSession implements StreamSession {
        private final InputStream body;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean firstByte = new AtomicBoolean();
        private volatile int timedOutPhase;   // 0=无 1=首字节 2=空闲（定时器线程写、读线程读）

        HttpStreamSession(InputStream body) {
            this.body = body;
        }

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                closeQuietly(body);
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        boolean firstByteArrived() { return firstByte.get(); }
        void markFirstByte() { firstByte.set(true); }
        void markTimedOut(int phase) { timedOutPhase = phase; }
        int timedOutPhase() { return timedOutPhase; }
    }
}
