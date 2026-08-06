package com.aigateway.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * OpenAI 兼容的聊天请求（V1 只保留需要的字段）。
 *
 * 学习要点：
 * - Java record 自动生成构造器、getter（accessor）、equals/hashCode/toString，
 *   非常适合做不可变的 DTO；
 * - {@code @JsonIgnoreProperties(ignoreUnknown = true)} 让 Jackson 忽略请求里
 *   多余的字段（如 temperature 之外的未知参数），避免反序列化失败；
 * - 字段名必须与客户端 JSON 一致（model / messages / stream / temperature / maxTokens）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRequest(
        String model,                 // 客户端传入的模型别名（对应 model.yml 中的 alias）
        List<Message> messages,       // 对话消息列表（role + content）
        Boolean stream,               // 是否流式；用 Boolean 而非 boolean，便于区分“未传”
        Double temperature,           // 采样温度（V1 暂不转发到上游，仅预留）
        Integer maxTokens             // 最大生成 token 数（V1 暂不转发，仅预留）
) {

    /** 一条对话消息：role 取值 system / user / assistant */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String content) {}

    /** 只有显式传了 true 才算流式请求；未传或传 false 都走非流式 */
    public boolean streaming() {
        return Boolean.TRUE.equals(stream);
    }

    /**
     * 返回一个 stream 被强制改写的新请求。
     * 用途：H3 流式转发时，无论客户端是否传 stream，都要确保发给上游的 body 带 stream=true。
     */
    public ChatRequest withStream(boolean streaming) {
        return new ChatRequest(model, messages, streaming, temperature, maxTokens);
    }
}
