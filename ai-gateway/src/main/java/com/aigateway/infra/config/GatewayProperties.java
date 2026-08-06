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
 *
 * 学习要点：
 * - {@code @ConfigurationProperties} 会把 model.yml 里以 gateway 开头的配置自动绑定到
 *   本类字段（宽松绑定：intervalSeconds ↔ interval-seconds 均可）；
 * - 字段带默认值（如 health 默认 5 秒），配置缺省时不报错；
 * - 用静态嵌套类表达“渠道定义 / 模型定义 / 候选定义”的层级结构，与 YAML 一一对应。
 */
@Configuration
@ConfigurationProperties(prefix = "gateway")
@Data
public class GatewayProperties {

    /** 健康检查参数：间隔、单次探活超时、连续失败多少次后判为不健康 */
    private Health health = new Health();

    /** 渠道列表（上游端点） */
    private List<ChannelDef> channels = new ArrayList<>();

    /** 模型别名 → 候选实例列表 */
    private List<ModelDef> models = new ArrayList<>();

    /** 健康检查参数定义 */
    @Data
    public static class Health {
        private long intervalSeconds = 5;        // 探活周期（秒）
        private long timeoutMs = 2000;           // 单次探活超时（毫秒）
        private int consecutiveFailures = 3;     // 连续失败多少次后标记不健康
    }

    /** 渠道定义：一个上游端点 */
    @Data
    public static class ChannelDef {
        private String id;              // 渠道 ID（唯一）
        private String provider;        // 供应商类型
        private String baseUrl;         // 上游基础地址
        private String credentialsRef = ""; // 密钥引用（env:XXX），默认为空 = 无需鉴权
        private int weight = 1;         // 渠道权重
    }

    /** 模型定义：一个对外别名下的候选列表 */
    @Data
    public static class ModelDef {
        private String alias;                        // 对外模型名（客户端传入）
        private List<CandidateDef> candidates = new ArrayList<>(); // 候选实例
    }

    /** 候选定义：绑定到某个渠道的某个上游模型 */
    @Data
    public static class CandidateDef {
        private String channelId;       // 引用的渠道
        private String model;           // 上游真实模型名
        private int weight = 1;         // 候选权重（同别名下按比例路由）
        private Capability capability;  // 能力画像（V2 调度用）
    }
}
