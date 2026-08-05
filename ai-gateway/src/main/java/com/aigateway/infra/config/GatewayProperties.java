package com.aigateway.infra.config;

import com.aigateway.core.domain.model.Capability;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关配置（前缀 gateway），对应 model.yml：
 * channels（渠道）+ models（别名 → 候选）+ health（健康检查参数）。
 */
@Configuration
@ConfigurationProperties(prefix = "gateway")
@Data
public class GatewayProperties {

    private Health health = new Health();
    private List<ChannelDef> channels = new ArrayList<>();
    private List<ModelDef> models = new ArrayList<>();

    @Data
    public static class Health {
        private long intervalSeconds = 5;
        private long timeoutMs = 2000;
        private int consecutiveFailures = 3;
    }

    @Data
    public static class ChannelDef {
        private String id;
        private String provider;
        private String baseUrl;
        private String credentialsRef = "";
        private int weight = 1;
    }

    @Data
    public static class ModelDef {
        private String alias;
        private List<CandidateDef> candidates = new ArrayList<>();
    }

    @Data
    public static class CandidateDef {
        private String channelId;
        private String model;
        private int weight = 1;
        private Capability capability;
    }
}
