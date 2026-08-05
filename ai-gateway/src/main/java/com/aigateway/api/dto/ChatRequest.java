package com.aigateway.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * OpenAI 兼容的聊天请求（V1 只保留需要的字段）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRequest(
        String model,
        List<Message> messages,
        Boolean stream,
        Double temperature,
        Integer maxTokens
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String content) {}

    public boolean streaming() {
        return Boolean.TRUE.equals(stream);
    }

    public ChatRequest withStream(boolean streaming) {
        return new ChatRequest(model, messages, streaming, temperature, maxTokens);
    }
}
