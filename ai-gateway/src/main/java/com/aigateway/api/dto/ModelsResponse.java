package com.aigateway.api.dto;

import java.util.List;

/**
 * GET /v1/models 响应（OpenAI 风格的最小实现）。
 *
 * data 列表中的每一项对应一个模型别名；object 固定为 "model"，与 OpenAI 协议保持一致。
 */
public record ModelsResponse(List<Model> data) {

    /** 单个模型条目：id 是别名，object 固定为 "model" */
    public record Model(String id, String object) {

        /** 便捷构造器：客户端只需传 id，object 自动补成 "model" */
        public Model(String id) {
            this(id, "model");
        }
    }
}
