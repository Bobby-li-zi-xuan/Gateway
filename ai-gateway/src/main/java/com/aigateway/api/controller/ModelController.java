package com.aigateway.api.controller;

import com.aigateway.api.dto.ModelsResponse;
import com.aigateway.state.registry.ModelRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ModelController {

    private final ModelRegistry registry;

    public ModelController(ModelRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/v1/models")
    public ModelsResponse models() {
        List<ModelsResponse.Model> data = registry.aliases().stream()
                .map(ModelsResponse.Model::new)
                .toList();
        return new ModelsResponse(data);
    }
}
