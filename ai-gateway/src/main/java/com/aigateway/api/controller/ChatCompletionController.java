package com.aigateway.api.controller;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.service.ChatGatewayService;
import com.aigateway.infra.web.RequestIdFilter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
public class ChatCompletionController {

    private final ChatGatewayService gatewayService;

    public ChatCompletionController(ChatGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @PostMapping("/v1/chat/completions")
    public Mono<ResponseEntity<?>> chat(@RequestBody ChatRequest request,
                                        ServerWebExchange exchange) {
        String requestId = requestId(exchange);
        if (request.streaming()) {
            Flux<ServerSentEvent<ChatChunk>> sse =
                    gatewayService.stream(request, requestId)
                            .map(chunk -> ServerSentEvent.builder(chunk).build());
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(sse));
        }
        return gatewayService.complete(request, requestId)
                .map(ResponseEntity::ok);
    }

    private String requestId(ServerWebExchange exchange) {
        return exchange.getAttributeOrDefault(RequestIdFilter.ATTR,
                UUID.randomUUID().toString());
    }
}
