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
 * 🖊 手敲 H5：本类 init() 中的启动校验规则尚未实现，
 * 请按《版本1-详细实施计划》第 7.3 节补全（见下方 TODO）。
 */
@Component
public class ModelRegistry {

    private final Map<String, List<ModelInstance>> byAlias = new ConcurrentHashMap<>();
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final SecretResolver secretResolver;

    public ModelRegistry(GatewayProperties props, SecretResolver secretResolver) {
        this.secretResolver = secretResolver;
        init(props);
    }

    private void init(GatewayProperties props) {
        // ============================================================
        // TODO H5（手敲）：启动校验
        // 1) 渠道：credentialsRef 必须可解析（secretResolver.resolve 抛错即启动失败）
        // 2) 模型：alias 非空；候选非空；权重 > 0；候选引用的渠道必须存在
        // 3) 重复 alias 应拒绝
        // 校验失败统一抛 GatewayException(500, "invalid_config", 可读信息)
        // 参考实现：见《版本1-详细实施计划》第 7.3 节 S5b
        // ============================================================

        // 1、渠道：解析密钥引用（缺失即启动失败）
        for(GatewayProperties.ChannelDef def : props.getChannels()){
            secretResolver.resolve(def.getCredentialsRef());        //只做校验
            channels.put(def.getId(), new Channel(
                        def.getId(), def.getProvider(), def.getBaseUrl(),
                        def.getCredentialsRef(), def.getWeight()
            ));
        }
        // 2. 模型候选，校验渠道存在、权重合法
        for (GatewayProperties.ModelDef def : props.getModels()) {
            if(def.getAlias() == null || def.getAlias().isBlank()){
                throw new GatewayException(500, "invalid_config", "models中存在空alias");
            }
            if(def.getCandidates().isEmpty()){
                throw new GatewayException(500, "invalid_config", "alias[" + def.getAlias() + "] 没有任何候选");
            }
            List<ModelInstance> instances = def.getCandidates().stream().map(c
            ->{
                Channel channel = channels.get(c.getChannelId());
                if(channel == null){
                    throw new GatewayException(500, "invalid_config", 
                            "alias[" + def.getAlias() + "] 引用了不存在的渠道："
                            + c.getChannelId()
                    );
                }
                if(c.getWeight() <= 0){
                    throw new GatewayException(500, "invalid_config",
                            "alias[" + def.getAlias() + "] 存在非正权重"
                            + c.getChannelId()
                    );
                }
                return new ModelInstance(
                        c.getChannelId() + ":" + c.getModel(),
                        def.getAlias(), c.getChannelId(), c.getModel(),
                        c.getWeight(), c.getCapability()
                );
            }
            ).toList();
            byAlias.put(def.getAlias(), List.copyOf(instances));
        }
        if(byAlias.isEmpty()){
            throw new GatewayException(500, "invalid_config", "未配置任何模型");
        }
    }

    public List<ModelInstance> findByAlias(String alias) {
        return byAlias.getOrDefault(alias, List.of());
    }

    /** 全部候选实例（健康检查遍历用，实施计划第 13 节 S13） */
    public List<ModelInstance> findAll() {
        return byAlias.values().stream()
                .flatMap(List::stream)
                .toList();
    }

    public Optional<Channel> findChannel(String channelId) {
        return Optional.ofNullable(channels.get(channelId));
    }

    public List<String> aliases() {
        return List.copyOf(byAlias.keySet());
    }
}
