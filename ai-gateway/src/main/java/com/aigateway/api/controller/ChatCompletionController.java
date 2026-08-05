package com.aigateway.api.controller;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.api.dto.ChatCompletion;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.service.ChatGatewayService;
import com.aigateway.infra.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

/**
 * OpenAI 兼容聊天接口（Spring MVC + 虚拟线程）。
 * 流式响应：在虚拟线程中调用 gatewayService.stream，逐 chunk 写入 SseEmitter。
 */
@RestController
public class ChatCompletionController {

    private final ChatGatewayService gatewayService;

    public ChatCompletionController(ChatGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        String requestId = requestId(httpRequest);
        if (request.streaming()) {
            return streamResponse(request, requestId);
        }
        ChatCompletion completion = gatewayService.complete(request, requestId);
        return ResponseEntity.ok(completion);
    }

    private ResponseEntity<SseEmitter> streamResponse(ChatRequest request, String requestId) {
        SseEmitter emitter = new SseEmitter(0L);
        Thread.ofVirtual().name("sse-" + requestId).start(() -> {
            try {
                gatewayService.stream(request, requestId, chunk -> {
                    try {
                        emitter.send(chunk);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    private String requestId(HttpServletRequest httpRequest) {
        Object attr = httpRequest.getAttribute(RequestIdFilter.ATTR);
        return attr != null ? attr.toString() : UUID.randomUUID().toString();
    }
}
