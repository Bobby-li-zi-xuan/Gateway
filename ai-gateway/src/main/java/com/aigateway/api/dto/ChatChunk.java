package com.aigateway.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI 流式响应分片（chat.completion.chunk）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatChunk(
        String id,
        String object,
        long created,
        String model,
        List<ChunkChoice> choices
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChunkChoice(int index, Delta delta,
                              @JsonProperty("finish_reason") String finishReason) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Delta(String content) {}
    }
}
