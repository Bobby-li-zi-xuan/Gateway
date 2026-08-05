package com.aigateway.api.dto;

import java.util.List;

/** GET /v1/models 响应（OpenAI 风格的最小实现）。 */
public record ModelsResponse(List<Model> data) {

    public record Model(String id, String object) {
        public Model(String id) {
            this(id, "model");
        }
    }
}
