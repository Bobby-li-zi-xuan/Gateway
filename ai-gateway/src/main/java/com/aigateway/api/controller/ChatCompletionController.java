package com.aigateway.api.controller;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.api.dto.ChatCompletion;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.service.ChatGatewayService;
import com.aigateway.infra.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI 兼容聊天接口（Spring MVC + 虚拟线程）。
 *
 * 职责：
 * 1. 接收客户端请求，统一生成 requestId 贯穿全链路；
 * 2. 根据 stream 参数分流：非流式直接返回完整 JSON；流式返回 SSE（Server-Sent Events）；
 * 3. V2：提取关键 header 为 metadata（tenant / canary_group），传入网关服务供示例插件消费。
 *
 * 流式实现要点：
 * - {@link SseEmitter} 是 Spring MVC 的异步响应对象，会自动把 send() 的内容按
 *   {@code data: ...} 帧格式输出给客户端；
 * - 发送过程跑在独立的虚拟线程上：先由网关主流程（ChatGatewayService）向上游逐个
 *   读取 chunk，再通过回调写入 SseEmitter，做到“边收边转”，而不是攒完再返回。
 */
@RestController
public class ChatCompletionController {

    private final ChatGatewayService gatewayService;

    public ChatCompletionController(ChatGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    /**
     * POST /v1/chat/completions（OpenAI 兼容）。
     *
     * @param request     客户端请求体（model / messages / stream 等）
     * @param httpRequest 用于取出 RequestIdFilter 写入的 requestId
     */
    @PostMapping("/v1/chat/completions")
    public Object chat(@RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        // 先取链路 ID：没有过滤器注入时（例如直接调用）再临时生成一个
        String requestId = requestId(httpRequest);
        // 流式与非流式走两条完全不同的响应路径
        if (request.streaming()) {
            return streamResponse(request, requestId, metadata(httpRequest));
        }
        // 非流式：阻塞等待上游返回完整结果（虚拟线程保证不占满系统线程）
        ChatCompletion completion = gatewayService.complete(request, requestId, metadata(httpRequest));
        return ResponseEntity.ok(completion);
    }

    /**
     * 流式响应：返回 SseEmitter，并立刻返回 HTTP 200 + text/event-stream。
     *
     * 注意：
     * - 必须“裸返回” SseEmitter，不能包在 ResponseEntity 里——Spring MVC 对
     *   ResponseEntity 走 HttpMessageConverter 路径，不支持 SseEmitter，
     *   裸返回才会被 SseEmitterReturnValueHandler 接管（计划文档 S9 的示例写错了，
     *   这里按正确行为实现）；
     * - 控制器方法返回后，Tomcat 会挂起该连接；真正的数据由下面的虚拟线程
     *   逐步写入 emitter，写完调用 complete() 结束，异常则 completeWithError() 断开。
     */
    private SseEmitter streamResponse(ChatRequest request, String requestId,
                                      Map<String, String> metadata) {
        // 0L 表示不设超时：流式连接时长由上游决定，不能按普通请求的读超时处理
        SseEmitter emitter = new SseEmitter(0L);
        // 每个流式请求一个虚拟线程，命名带上 requestId 方便排查
        Thread.ofVirtual().name("sse-" + requestId).start(() -> {
            try {
                // gatewayService.stream 会阻塞读取上游 SSE 并逐 chunk 回调
                gatewayService.stream(request, requestId, metadata, chunk -> {
                    try {
                        // 一个 chunk 就是 OpenAI 格式的一段增量（{choices:[{delta:{content}}]}）
                        emitter.send(chunk);
                    } catch (IOException e) {
                        // 客户端断开连接时 send 会抛 IOException，包装后由外层统一处理
                        throw new UncheckedIOException(e);
                    }
                });
                // 全部 chunk 发送完毕，正常结束 SSE 流
                emitter.complete();
            } catch (Exception e) {
                // 任一步骤失败：让客户端收到错误结束帧（而非悬挂等待）
                emitter.completeWithError(e);
            }
        });
        // 裸返回 SseEmitter：Spring 自动按 data: 帧格式输出并设好 media type
        return emitter;
    }

    /**
     * V2：把关键 header 提取成 metadata（示例插件消费），传入 gatewayService.complete/stream。
     */
    private Map<String, String> metadata(HttpServletRequest httpRequest) {
        return Map.of(
                "tenant", header(httpRequest, "X-Tenant-Id", "default"),
                "canary_group", header(httpRequest, "X-Canary-Group", "stable"));
    }

    private String header(HttpServletRequest req, String name, String fallback) {
        String value = req.getHeader(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 从请求属性中取 requestId（由 RequestIdFilter 在过滤器中生成并写入）。
     * 若属性不存在（例如测试环境没有走过滤器），则这里兜底生成一个。
     */
    private String requestId(HttpServletRequest httpRequest) {
        Object attr = httpRequest.getAttribute(RequestIdFilter.ATTR);
        return attr != null ? attr.toString() : UUID.randomUUID().toString();
    }
}
