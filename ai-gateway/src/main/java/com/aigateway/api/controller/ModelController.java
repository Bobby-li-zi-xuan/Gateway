package com.aigateway.api.controller;

import com.aigateway.api.dto.ModelsResponse;
import com.aigateway.state.registry.ModelRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型列表接口：GET /v1/models（OpenAI 兼容）。
 *
 * 与聊天接口拆分到两个 Controller，职责更清晰：
 * - {@link ChatCompletionController} 只负责 /v1/chat/completions；
 * - 本类只负责把注册中心里的模型别名暴露给客户端。
 */
@RestController
public class ModelController {

    private final ModelRegistry registry;

    public ModelController(ModelRegistry registry) {
        this.registry = registry;
    }

    /**
     * 返回当前所有可用模型别名，例如：{"data":[{"id":"qwen","object":"model"}, ...]}。
     * 注意：这里列出的是“别名”，而非每个渠道/上游模型实例。
     */
    @GetMapping("/v1/models")
    public ModelsResponse models() {
        List<ModelsResponse.Model> data = registry.aliases().stream()
                .map(ModelsResponse.Model::new)
                .toList();
        return new ModelsResponse(data);
    }
}
