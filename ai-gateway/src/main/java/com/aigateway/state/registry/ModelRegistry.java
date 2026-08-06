package com.aigateway.state.registry;

import com.aigateway.core.domain.model.Channel;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.infra.config.GatewayProperties;
import com.aigateway.infra.config.SecretResolver;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 模型注册中心：维护“别名 → 候选实例列表”与渠道表。
 *
 * 🖊 手敲 H5：启动校验规则（init() 中）按《版本1-详细实施计划》第 7.3 节实现，
 * 学习时请逐条对照 TODO 说明，理解“尽早失败”的工程思想：
 * 配置错误应该在启动阶段报出来，而不是等到线上请求才返回 500。
 *
 * 数据结构：
 * - byAlias：alias → 候选实例列表（路由查询入口）；
 * - channels：channelId → 渠道信息（baseUrl / 鉴权 / 权重）；
 * - strategyByAlias：alias → 策略名（V2 PolicyManager 交叉校验与运行时查询用）。
 *
 * 学习要点：
 * - {@link ConcurrentHashMap} 保证并发安全（虽然 V1 启动后基本只读）；
 * - 对外返回 {@link List#copyOf} 不可变列表，防止调用方意外修改内部状态；
 * - V2 新字段（价格/质量/延迟）缺省时在此处填默认值，保证旧配置可启动。
 */
@Component
public class ModelRegistry {

    /** 别名 → 候选实例列表（不可变列表） */
    private final Map<String, List<ModelInstance>> byAlias = new ConcurrentHashMap<>();

    /** 渠道 ID → 渠道信息 */
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    /** 别名 → 策略名（V2：缺省 balanced） */
    private final Map<String, String> strategyByAlias = new ConcurrentHashMap<>();

    private final SecretResolver secretResolver;

    /** 构造时即完成加载与校验：任何校验失败都会让 Spring 启动失败 */
    public ModelRegistry(GatewayProperties props, SecretResolver secretResolver) {
        this.secretResolver = secretResolver;
        init(props);
    }

    /**
     * 加载渠道与模型配置，并做启动校验。
     *
     * ============================================================
     * H5（手敲）：启动校验规则（当前为已完成版，对照文档第 7.3 节理解）
     * 1) 渠道：credentialsRef 必须可解析（secretResolver.resolve 抛错即启动失败）
     * 2) 模型：alias 非空；候选非空；权重 > 0；候选引用的渠道必须存在
     * 3) 重复 alias 应拒绝
     * 校验失败统一抛 GatewayException(500, "invalid_config", 可读信息)
     * ============================================================
     * V2 增量（脚手架已完成）：
     * 4) 候选新字段：priceIn/priceOut >= 0；qualityScore 若配置必须在 0~1；
     *    latencyProfileMs 若配置必须 > 0
     * 5) qualityScore 缺省由 qualityLevel 推导；latencyProfileMs 缺省 1000
     * 6) 记录 alias → strategy（缺省 balanced）
     */
    private void init(GatewayProperties props) {
        // 1. 渠道：先解析密钥引用（只做校验、不保存值），缺失即启动失败
        for (GatewayProperties.ChannelDef def : props.getChannels()) {
            secretResolver.resolve(def.getCredentialsRef());
            channels.put(def.getId(), new Channel(
                    def.getId(), def.getProvider(), def.getBaseUrl(),
                    def.getCredentialsRef(), def.getWeight()));
        }

        // 2. 模型候选：逐条校验 alias / 候选 / 渠道 / 权重 / 重复 alias / 新字段
        Set<String> seenAliases = new HashSet<>();
        for (GatewayProperties.ModelDef def : props.getModels()) {
            if (def.getAlias() == null || def.getAlias().isBlank()) {
                throw new GatewayException(500, "invalid_config", "models 中存在空 alias");
            }
            // 重复 alias 会让后配置的静默覆盖先配置的，客户端路由结果不可预期，必须拒绝
            if (!seenAliases.add(def.getAlias())) {
                throw new GatewayException(500, "invalid_config",
                        "重复的 alias: " + def.getAlias());
            }
            if (def.getCandidates().isEmpty()) {
                throw new GatewayException(500, "invalid_config",
                        "alias[" + def.getAlias() + "] 没有任何候选");
            }
            // V2：记录别名引用的策略名（PolicyManager 启动时交叉校验）
            strategyByAlias.put(def.getAlias(),
                    def.getStrategy() == null || def.getStrategy().isBlank()
                            ? "balanced" : def.getStrategy());

            List<ModelInstance> instances = def.getCandidates().stream().map(c -> {
                Channel channel = channels.get(c.getChannelId());
                // 候选引用的渠道必须已配置
                if (channel == null) {
                    throw new GatewayException(500, "invalid_config",
                            "alias[" + def.getAlias() + "] 引用了不存在的渠道: " + c.getChannelId());
                }
                // 权重必须为正：权重是比例分母，<=0 会导致路由算法出问题
                if (c.getWeight() <= 0) {
                    throw new GatewayException(500, "invalid_config",
                            "alias[" + def.getAlias() + "] 存在非正权重");
                }
                // V2：价格/质量/延迟画像校验（尽早失败）
                if (c.getPriceIn() < 0 || c.getPriceOut() < 0) {
                    throw new GatewayException(500, "invalid_config",
                            "alias[" + def.getAlias() + "] 存在负价格");
                }
                if (c.getQualityScore() != null
                        && (c.getQualityScore() < 0 || c.getQualityScore() > 1)) {
                    throw new GatewayException(500, "invalid_config",
                            "alias[" + def.getAlias() + "] 的 qualityScore 必须在 0~1");
                }
                if (c.getLatencyProfileMs() != null && c.getLatencyProfileMs() <= 0) {
                    throw new GatewayException(500, "invalid_config",
                            "alias[" + def.getAlias() + "] 的 latencyProfileMs 必须 > 0");
                }
                // instanceId = channelId:model，同一渠道下不同模型互不冲突
                return new ModelInstance(
                        c.getChannelId() + ":" + c.getModel(),
                        def.getAlias(), c.getChannelId(), c.getModel(),
                        c.getWeight(), c.getCapability(),
                        c.getPriceIn(), c.getPriceOut(),
                        c.getQualityScore() != null ? c.getQualityScore()
                                : deriveQuality(c.getCapability()),
                        c.getLatencyProfileMs() != null ? c.getLatencyProfileMs() : 1000L);
            }).toList();
            byAlias.put(def.getAlias(), List.copyOf(instances));
        }

        // 3. 空配置兜底：一个模型都没有的网关没有意义，直接启动失败
        if (byAlias.isEmpty()) {
            throw new GatewayException(500, "invalid_config", "未配置任何模型");
        }
    }

    /** 按别名取候选列表；别名不存在时返回空列表（由上层决定怎么报错） */
    public List<ModelInstance> findByAlias(String alias) {
        return byAlias.getOrDefault(alias, List.of());
    }

    /** 所有候选实例（跨别名按 instanceId 去重后的完整列表，健康检查遍历用） */
    public List<ModelInstance> findAll() {
        // 同一渠道+模型可能出现在多个别名下（如 deepseek-code 同时被两个别名引用），
        // 健康检查按渠道去重并不受影响，但这里仍按 instanceId 去重，保证返回列表无重复
        return byAlias.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toMap(ModelInstance::instanceId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new))
                .values().stream()
                .toList();
    }

    /** 按渠道 ID 查渠道信息；不存在返回空 */
    public Optional<Channel> findChannel(String channelId) {
        return Optional.ofNullable(channels.get(channelId));
    }

    /** 所有模型别名（GET /v1/models 用），返回不可变副本 */
    public List<String> aliases() {
        return List.copyOf(byAlias.keySet());
    }

    /** V2：别名 → 策略名（缺省 balanced；PolicyManager 启动校验与运行时查询用） */
    public String strategyOf(String alias) {
        return strategyByAlias.getOrDefault(alias, "balanced");
    }

    /** V2：qualityScore 缺省时从能力画像的质量档位推导（未配置能力 → 0.5） */
    private static double deriveQuality(com.aigateway.core.domain.model.Capability capability) {
        if (capability == null || capability.getQualityLevel() == null) {
            return 0.5;
        }
        return switch (capability.getQualityLevel()) {
            case "QUALITY_HIGH" -> 0.9;
            case "QUALITY_MEDIUM" -> 0.6;
            case "FAST" -> 0.3;
            default -> 0.5;
        };
    }
}
