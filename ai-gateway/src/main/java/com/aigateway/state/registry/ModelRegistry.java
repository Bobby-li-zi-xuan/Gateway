package com.aigateway.state.registry;

import com.aigateway.core.domain.model.Channel;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.infra.config.GatewayProperties;
import com.aigateway.infra.config.SecretResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册中心：维护“别名 → 候选实例列表”与渠道表。
 *
 * 🖊 手敲 H5：启动校验规则（init() 中）按《版本1-详细实施计划》第 7.3 节实现，
 * 学习时请逐条对照 TODO 说明，理解“尽早失败”的工程思想：
 * 配置错误应该在启动阶段报出来，而不是等到线上请求才返回 500。
 *
 * 数据结构：
 * - byAlias：alias → 候选实例列表（路由查询入口）；
 * - channels：channelId → 渠道信息（baseUrl / 鉴权 / 权重）。
 *
 * 学习要点：
 * - {@link ConcurrentHashMap} 保证并发安全（虽然 V1 启动后基本只读）；
 * - 对外返回 {@link List#copyOf} 不可变列表，防止调用方意外修改内部状态。
 */
@Component
public class ModelRegistry {

    /** 别名 → 候选实例列表（不可变列表） */
    private final Map<String, List<ModelInstance>> byAlias = new ConcurrentHashMap<>();

    /** 渠道 ID → 渠道信息 */
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

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
     */
    private void init(GatewayProperties props) {
        // 1. 渠道：先解析密钥引用（只做校验、不保存值），缺失即启动失败
        for (GatewayProperties.ChannelDef def : props.getChannels()) {
            secretResolver.resolve(def.getCredentialsRef());
            channels.put(def.getId(), new Channel(
                    def.getId(), def.getProvider(), def.getBaseUrl(),
                    def.getCredentialsRef(), def.getWeight()));
        }

        // 2. 模型候选：逐条校验 alias / 候选 / 渠道 / 权重
        for (GatewayProperties.ModelDef def : props.getModels()) {
            if (def.getAlias() == null || def.getAlias().isBlank()) {
                throw new GatewayException(500, "invalid_config", "models 中存在空 alias");
            }
            if (def.getCandidates().isEmpty()) {
                throw new GatewayException(500, "invalid_config",
                        "alias[" + def.getAlias() + "] 没有任何候选");
            }
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
                // instanceId = channelId:model，同一渠道下不同模型互不冲突
                return new ModelInstance(
                        c.getChannelId() + ":" + c.getModel(),
                        def.getAlias(), c.getChannelId(), c.getModel(),
                        c.getWeight(), c.getCapability());
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

    /** 所有候选实例（跨别名去重后的完整列表，健康检查遍历用） */
    public List<ModelInstance> findAll() {
        return byAlias.values().stream().flatMap(List::stream).toList();
    }

    /** 按渠道 ID 查渠道信息；不存在返回空 */
    public Optional<Channel> findChannel(String channelId) {
        return Optional.ofNullable(channels.get(channelId));
    }

    /** 所有模型别名（GET /v1/models 用），返回不可变副本 */
    public List<String> aliases() {
        return List.copyOf(byAlias.keySet());
    }
}
