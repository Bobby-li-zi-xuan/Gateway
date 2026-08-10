package com.aigateway.execution.connector;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.api.dto.ChatCompletion;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.Channel;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.infra.config.SecretResolver;
import com.aigateway.state.registry.ModelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * OpenAI 兼容上游连接器（阻塞式，JDK HttpClient）。
 *
 * 职责：把网关内部请求转成 HTTP 调用发给上游渠道，再把上游响应转回类型化 DTO。
 * 两种模式：
 * - {@link #complete}：非流式，一次性等待完整 JSON 响应（已实现）；
 * - {@link #stream} + {@link #parseSseLine}：流式，逐行读取上游 SSE 并回调转发（已实现，H3）。
 *
 * 学习要点：
 * - 使用 JDK 自带 HttpClient，不引入额外依赖；阻塞式调用由虚拟线程承载；
 * - 鉴权头通过 {@link SecretResolver} 从环境变量读取，配置里不出现密钥明文；
 * - 统一把上游错误转换成 {@link GatewayException}，交给全局异常处理器。
 */
@Component
public class OpenAIConnector {

    /** 请求级超时：指“从发请求到收到完整响应”的最长等待；与连接超时（500ms）是两回事 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;       // 连接池复用（HttpClientFactory 提供）
    private final ModelRegistry registry;      // 查渠道 baseUrl / 鉴权配置
    private final SecretResolver secretResolver; // env: 密钥引用解析
    private final ObjectMapper objectMapper;   // 请求序列化 + 响应/SSE 反序列化

    public OpenAIConnector(HttpClient httpClient,
                           ModelRegistry registry,
                           SecretResolver secretResolver,
                           ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.registry = registry;
        this.secretResolver = secretResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * 非流式调用（已实现，作为 H3 流式实现的模板）。
     *
     * 流程：拼 URL → 写 JSON body → 加鉴权头 → send 等待响应 →
     *       非 2xx 抛异常 → 2xx 反序列化为 {@link ChatCompletion}。
     */
    public ChatCompletion complete(ModelInstance instance, ChatRequest request) {
        // 1. 构建请求：POST {渠道baseUrl}/v1/chat/completions
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(channelBaseUrl(instance) + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(request)));
        // 2. 渠道需要鉴权时附加 Authorization: Bearer xxx
        applyAuth(builder, instance);
        try {
            // 3. 阻塞发送：虚拟线程挂起等待，不占用平台线程
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            // 4. 非 2xx：把上游状态码和响应体带进错误信息，方便排查
            if (response.statusCode() / 100 != 2) {
                throw new GatewayException(502, "upstream_error",
                        "上游返回 " + response.statusCode() + ": " + response.body());
            }
            // 5. 反序列化为类型化 DTO（record 默认忽略未知字段）
            return objectMapper.readValue(response.body(), ChatCompletion.class);
        } catch (GatewayException e) {
            throw e; // 业务异常直接上抛，不做二次包装
        } catch (IOException | InterruptedException e) {
            // 中断要恢复线程的中断标记（线程池 / 虚拟线程最佳实践）
            Thread.currentThread().interrupt();
            throw new GatewayException(502, "upstream_failed", "调用上游失败: " + e.getMessage(), e);
        }
    }

    /**
     * 流式调用（已实现，H3）。
     *
     * 流程：构建 Accept: text/event-stream 的请求 → 用 InputStream 接收 →
     *       BufferedReader 逐行读取 → parseSseLine 解析 → consumer 逐 chunk 转发。
     *
     * 学习要点：
     * - 一次只读一行并立刻转发，不要等整个响应读完（否则失去流式意义）；
     * - 结束标志是 data: [DONE]，由 parseSseLine 返回 null 表示“本行忽略”。
     */
    public void stream(ModelInstance instance, ChatRequest request, Consumer<ChatChunk> consumer) {
        // 1. 构建流式请求：Accept: text/event-stream；body 强制 stream=true
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(channelBaseUrl(instance) + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(request.withStream(true))));
        applyAuth(builder, instance);
        try{
            // 2. 用InputStream接收；不把整个响应体读进内存，边读边转发
            HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());

            // 3. 非2xx，InputStream需要手动读完错误体再抛异常
            if(response.statusCode() / 100 != 2){
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new GatewayException(502, "upstream_error", "上游返回 " 
                    + response.statusCode() + ": " + body
                );
            }

            // 4. 逐行读取SSE：一行一行解析转发，不攒批
            try(BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8)
            )){
                String line;
                while((line = reader.readLine()) != null){
                    ChatChunk chunk = parseSseLine(line);
                    if(chunk != null){
                        consumer.accept(chunk);
                    }
                }
            }
        }catch(GatewayException e){
            throw e;
        }catch(IOException | InterruptedException e){
            Thread.currentThread().interrupt();
            throw new GatewayException(502, "upstream_failed", "流式调用上游失败：" + e.getMessage(), e);
        }
    }

    /**
     * SSE 行解析（已实现，H3）。
     *
     * 规则：
     * - "data: {...}" → objectMapper.readValue(payload, ChatChunk.class)
     * - "data: [DONE]" 或非 data 行 → 返回 null（忽略该行）
     * - JSON 解析失败 → GatewayException(502, "bad_upstream_sse", ...)
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
            throw new GatewayException(502, "bad_upstream_sse",
                    "上游返回了无法解析的 SSE 数据: " + payload);
        }
    }

    /**
     * 按渠道配置附加鉴权头。
     * 只有 needAuth()（配置了 credentialsRef）的渠道才会走到这里；
     * 密钥值来自 SecretResolver（env: 引用），环境变量未设置时 resolve 返回空。
     */
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
}
