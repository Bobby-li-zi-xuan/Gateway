package com.aigateway.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI 流式响应分片（object 固定为 chat.completion.chunk）。
 *
 * 流式原理：上游把完整回复切成多个 chunk 逐个发送，每个 chunk 的 choices[0].delta.content
 * 是一段增量文本；客户端把所有 delta 拼起来就是完整回复。最后一个 chunk 带
 * finish_reason="stop" 表示结束。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatChunk(
        String id,                // 与整个流共享的响应 ID
        String object,            // 固定为 "chat.completion.chunk"
        long created,             // 创建时间（Unix 秒）
        String model,             // 上游模型名
        List<ChunkChoice> choices // 本次分片的选择（通常只有一个）
) {

    /** 分片选择：delta 是增量内容；finish_reason 仅在最后一个 chunk 非空 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChunkChoice(int index, Delta delta,
                              @JsonProperty("finish_reason") String finishReason) {

        /** 增量内容：中间 chunk 有 content；结束 chunk 的 content 为空 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Delta(String content) {}
    }
}
