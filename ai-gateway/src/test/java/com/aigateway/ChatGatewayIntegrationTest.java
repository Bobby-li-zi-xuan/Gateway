package com.aigateway;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.mock.MockApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试（计划第 16.2 节）：启动真实网关（随机端口）+ 两个 mock 实例，验证 4 个场景：
 * 1. 非流式返回 OpenAI 格式；
 * 2. stream=true 返回 SSE 且最后一个 chunk 的 finish_reason=stop；
 * 3. mock-a 故障率调 100% 后，请求仍成功（降级到 mock-b）；
 * 4. mock-b 健康置 DOWN 后，/v1/models 仍返回但路由只走 mock-a。
 *
 * 说明：用 SpringApplicationBuilder 手动启动三个上下文（而非 @SpringBootTest），
 * 保证 mock 先于网关启动、端口可动态注入网关配置（覆盖 model.yml 的 baseUrl）。
 * 健康检查参数覆盖为 1 秒/1 次，加快剔除速度，避免测试等待 15 秒。
 */
class ChatGatewayIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ConfigurableApplicationContext mockA;
    private static ConfigurableApplicationContext mockB;
    private static ConfigurableApplicationContext gateway;

    private static String gatewayUrl;
    private static String mockAUrl;
    private static String mockBUrl;
    private static HttpClient client;

    private static final String CHAT_BODY = "{\"model\":\"qwen\","
            + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

    @BeforeAll
    static void startAll() {
        // 拓扑固定端口：mock 18001/18002、网关 18003（见 test/resources/model.yml）。
        // 网关上下文读取测试专用配置（test-classes 优先级高于 main），无需命令行覆盖渠道；
        // mock 上下文用 --server.port 命令行参数覆盖同 classpath 上的测试配置。
        mockA = startMock("qwen-large", 18001);
        mockB = startMock("qwen-small", 18002);
        mockAUrl = "http://localhost:18001";
        mockBUrl = "http://localhost:18002";

        // 用测试专用启动类（收窄扫描范围，避免 mock 模块的同类名 bean 冲突）
        gateway = new SpringApplicationBuilder(TestGatewayApplication.class)
                .web(WebApplicationType.SERVLET)
                .run("--server.port=18003");
        gatewayUrl = "http://localhost:18003";
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void stopAll() {
        for (ConfigurableApplicationContext ctx : List.of(gateway, mockB, mockA)) {
            if (ctx != null) {
                ctx.close();
            }
        }
    }

    private static ConfigurableApplicationContext startMock(String model, int port) {
        return new SpringApplicationBuilder(MockApplication.class)
                .web(WebApplicationType.SERVLET)
                .run("--server.port=" + port, "--mock.model=" + model);
    }

    private static HttpResponse<String> post(String url, String body) throws IOException, InterruptedException {
        return post(url, body, Map.of());
    }

    private static HttpResponse<String> post(String url, String body, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<InputStream> postStream(String url, String body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private static HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void nonStreaming_shouldReturnOpenAiFormat() throws Exception {
        HttpResponse<String> resp = post(gatewayUrl + "/v1/chat/completions", CHAT_BODY);

        assertThat(resp.statusCode()).isEqualTo(200);
        Map<?, ?> body = JSON.readValue(resp.body(), Map.class);
        assertThat(body.get("object")).isEqualTo("chat.completion");
        assertThat(body.get("choices")).asList().isNotEmpty();
        Map<?, ?> message = (Map<?, ?>) ((List<?>) body.get("choices")).get(0);
        assertThat(((Map<?, ?>) message.get("message")).get("content")).asString().isNotBlank();
    }

    @Test
    void streaming_shouldReturnSseEndingWithStop() throws Exception {
        String body = CHAT_BODY.substring(0, CHAT_BODY.length() - 1) + ",\"stream\":true}";
        HttpResponse<InputStream> resp = postStream(gatewayUrl + "/v1/chat/completions", body);

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("content-type").orElse(""))
                .contains("text/event-stream");

        // 逐行读 SSE，收集所有 data: 行
        List<String> dataLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    dataLines.add(line.substring(5).trim());
                }
            }
        }

        assertThat(dataLines).isNotEmpty();
        // 首 chunk 带增量内容
        ChatChunk first = JSON.readValue(dataLines.get(0), ChatChunk.class);
        assertThat(first.object()).isEqualTo("chat.completion.chunk");
        assertThat(first.choices()).isNotEmpty();
        // 末 chunk 的 finish_reason=stop
        ChatChunk last = JSON.readValue(dataLines.get(dataLines.size() - 1), ChatChunk.class);
        assertThat(last.choices().get(0).finishReason()).isEqualTo("stop");
    }

    @Test
    void mockAAt100PercentFailure_shouldFallbackToMockB() throws Exception {
        post(mockAUrl + "/mock/behavior", "{\"errorRate\":1.0}");

        try {
            // 多次请求：即使首次选中 mock-a（失败）也能切换成功
            for (int i = 0; i < 3; i++) {
                HttpResponse<String> resp = post(gatewayUrl + "/v1/chat/completions", CHAT_BODY);
                assertThat(resp.statusCode()).isEqualTo(200);
                Map<?, ?> body = JSON.readValue(resp.body(), Map.class);
                assertThat(body.get("model")).isEqualTo("qwen-small"); // 降级到 mock-b
            }
        } finally {
            post(mockAUrl + "/mock/behavior", "{\"errorRate\":0.0}"); // 恢复，避免影响其它用例
        }
    }

    @Test
    void mockBDown_shouldRouteOnlyToMockA() throws Exception {
        post(mockBUrl + "/mock/behavior", "{\"health\":false}");
        // 等一个检查周期（intervalSeconds=1 + 1 次失败即剔除），多等 2 秒保证生效
        Thread.sleep(3000);

        try {
            // /v1/models 仍可用（GET）
            HttpResponse<String> models = get(gatewayUrl + "/v1/models");
            assertThat(models.statusCode()).isEqualTo(200);

            // 路由只走 mock-a（qwen 的候选只剩健康渠道）
            for (int i = 0; i < 3; i++) {
                HttpResponse<String> resp = post(gatewayUrl + "/v1/chat/completions", CHAT_BODY);
                assertThat(resp.statusCode()).isEqualTo(200);
                Map<?, ?> body = JSON.readValue(resp.body(), Map.class);
                assertThat(body.get("model")).isEqualTo("qwen-large");
            }
        } finally {
            post(mockBUrl + "/mock/behavior", "{\"health\":true}"); // 恢复，避免影响其它用例
        }
    }

    // ============ V2 场景（计划 20.2）============

    private static final String COMPLEX_BODY = "{\"model\":\"qwen\","
            + "\"messages\":[{\"role\":\"user\",\"content\":\"请写一段 Java 代码实现快速排序\"}]}";

    @Test
    void complexRequest_shouldHitConditionalRouteToQwenLarge() throws Exception {
        // 场景 A-1：插件零侵入生效——复杂度识别插件写 COMPLEX 信号，
        // simple-task 条件策略命中 → 只选 mock-a:qwen-large
        HttpResponse<String> resp = post(gatewayUrl + "/v1/chat/completions", COMPLEX_BODY);

        assertThat(resp.statusCode()).isEqualTo(200);
        Map<?, ?> body = JSON.readValue(resp.body(), Map.class);
        assertThat(body.get("model")).isEqualTo("qwen-large");
    }

    @Test
    void experimentalGroup_shouldRouteToQwenSmall() throws Exception {
        // 场景 A-2：金丝雀分组——experimental 组白名单只含 mock-b:qwen-small
        HttpResponse<String> resp = post(gatewayUrl + "/v1/chat/completions", CHAT_BODY,
                Map.of("X-Canary-Group", "experimental"));

        assertThat(resp.statusCode()).isEqualTo(200);
        Map<?, ?> body = JSON.readValue(resp.body(), Map.class);
        assertThat(body.get("model")).isEqualTo("qwen-small");
    }

    @Test
    void debugEndpoint_shouldReturnCompleteScoringDetails() throws Exception {
        // 场景 B：可解释路由——普通请求（回退 balanced 多目标）后，
        // /v1/debug/decisions 返回的打分明细结构完整
        HttpResponse<String> chat = post(gatewayUrl + "/v1/chat/completions", CHAT_BODY);
        assertThat(chat.statusCode()).isEqualTo(200);

        HttpResponse<String> debug = get(gatewayUrl + "/v1/debug/decisions?limit=5");
        assertThat(debug.statusCode()).isEqualTo(200);
        Map<?, ?> body = JSON.readValue(debug.body(), Map.class);
        assertThat((Integer) body.get("count")).isGreaterThan(0);

        List<?> decisions = (List<?>) body.get("decisions");
        Map<?, ?> first = (Map<?, ?>) decisions.get(0);
        assertThat(first.get("scoringDetails")).asList().isNotEmpty();
        for (Object item : (List<?>) first.get("scoringDetails")) {
            Map<?, ?> d = (Map<?, ?>) item;
            // 明细字段齐全：instanceId / raw / normalized / weights / finalScore
            assertThat(d.get("instanceId")).isNotNull();
            assertThat(d.get("raw")).isInstanceOf(Map.class);
            assertThat(d.get("normalized")).isInstanceOf(Map.class);
            assertThat(d.get("weights")).isInstanceOf(Map.class);
            assertThat(d.get("finalScore")).isInstanceOf(Number.class);
        }
    }

    @Test
    void clearCandidatesPlugin_shouldReturn503CandidatesCleared() throws Exception {
        // 场景 C：fail-closed——独立网关实例（端口 18004），spring.config.import 组合加载
        // model.yml + model-failclosed.yml（后者整体替换 plugins 段，clear-candidates enabled=true）。
        // 注：不用 --gateway.plugins[2].config.enabled=true 覆盖——Spring Boot 对 list 的
        // indexed property 不支持与 yaml 合并（"elements were left unbound"）。
        ConfigurableApplicationContext failClosedGateway = null;
        try {
            failClosedGateway = new SpringApplicationBuilder(TestGatewayApplication.class)
                    .web(WebApplicationType.SERVLET)
                    .run("--server.port=18004",
                            "--spring.config.import=classpath:model.yml,classpath:model-failclosed.yml");
            HttpResponse<String> resp = post(
                    "http://localhost:18004/v1/chat/completions", CHAT_BODY);

            assertThat(resp.statusCode()).isEqualTo(503);
            Map<?, ?> body = JSON.readValue(resp.body(), Map.class);
            assertThat(body.get("type")).isEqualTo("candidates_cleared");
        } finally {
            if (failClosedGateway != null) {
                failClosedGateway.close();
            }
        }
    }
}
