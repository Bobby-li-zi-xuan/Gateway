package com.aigateway.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI 非流式响应（字段与上游 JSON 一一对应）。
 *
 * 学习要点：
 * - Jackson 默认不做下划线/驼峰互转，JSON 里的 {@code finish_reason} / {@code prompt_tokens}
 *   必须用 {@code @JsonProperty} 显式映射到 Java 字段；
 * - record 嵌套 record 组织“响应 → choices → message/usage”的层级结构，类型安全。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletion(
        String id,               // 响应 ID，如 chatcmpl-xxxx
        String object,           // 固定为 "chat.completion"
        long created,            // 创建时间（Unix 秒）
        String model,            // 实际使用的上游模型名
        List<Choice> choices,    // 候选回复列表（V1 上游一般只回一个）
        Usage usage              // token 用量统计
) {

    /** 单个回复候选：index 从 0 开始；finish_reason 标识结束原因（stop / length 等） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(int index, Message message,
                         @JsonProperty("finish_reason") String finishReason) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Message(String role, String content) {}
    }

    /** token 统计：JSON 字段是 snake_case，需要显式映射 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(@JsonProperty("prompt_tokens") int promptTokens,
                        @JsonProperty("completion_tokens") int completionTokens,
                        @JsonProperty("total_tokens") int totalTokens) {}
}
