package com.aigateway.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI 非流式响应（字段与上游 JSON 一一对应）。
 * 注意：Jackson 默认不做下划线转换，snake_case 字段用 @JsonProperty 显式映射。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletion(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        Usage usage
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(int index, Message message,
                         @JsonProperty("finish_reason") String finishReason) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Message(String role, String content) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(@JsonProperty("prompt_tokens") int promptTokens,
                        @JsonProperty("completion_tokens") int completionTokens,
                        @JsonProperty("total_tokens") int totalTokens) {}
}
