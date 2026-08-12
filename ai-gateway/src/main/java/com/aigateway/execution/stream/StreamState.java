package com.aigateway.execution.stream;

import com.aigateway.api.dto.ChatChunk;

/**
 * 流式规范化状态（脚手架，代码段 S11 配套）：
 * 记住整个流首个出现的非空 id / created / model / usage，后续 chunk 缺字段时复用——
 * 保证客户端收到的整个流共享同一个响应 ID（OpenAI 语义）。
 *
 * 仅被 StreamProxy.normalize 读写，单线程使用，无需同步。
 */
final class StreamState {

    private String id;
    private long created;
    private String model;
    private ChatChunk.Usage usage;

    StreamState(String model) {
        this.model = model;
    }

    String id() { return id; }

    void id(String id) {
        if (id != null) this.id = id;
    }

    long created() { return created; }

    void created(long created) {
        if (created != 0) this.created = created;
    }

    String model() { return model; }

    void model(String model) {
        if (model != null) this.model = model;
    }

    ChatChunk.Usage usage() { return usage; }

    void usage(ChatChunk.Usage usage) {
        if (usage != null) this.usage = usage;
    }
}
