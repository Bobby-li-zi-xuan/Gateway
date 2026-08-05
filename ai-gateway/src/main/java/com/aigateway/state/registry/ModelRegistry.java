package com.aigateway.state.registry;

import com.aigateway.core.domain.model.Channel;
import com.aigateway.core.domain.model.ModelInstance;
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

        for (GatewayProperties.ChannelDef def : props.getChannels()) {
            channels.put(def.getId(), new Channel(
                    def.getId(), def.getProvider(), def.getBaseUrl(),
                    def.getCredentialsRef(), def.getWeight()));
        }

        for (GatewayProperties.ModelDef def : props.getModels()) {
            List<ModelInstance> instances = new ArrayList<>();
            for (GatewayProperties.CandidateDef c : def.getCandidates()) {
                Channel channel = channels.get(c.getChannelId());
                instances.add(new ModelInstance(
                        c.getChannelId() + ":" + c.getModel(),
                        def.getAlias(), c.getChannelId(), c.getModel(),
                        c.getWeight(), c.getCapability()));
            }
            byAlias.put(def.getAlias(), List.copyOf(instances));
        }
    }

    public List<ModelInstance> findByAlias(String alias) {
        return byAlias.getOrDefault(alias, List.of());
    }

    public List<ModelInstance> findAll() {
        return byAlias.values().stream().flatMap(List::stream).toList();
    }

    public Optional<Channel> findChannel(String channelId) {
        return Optional.ofNullable(channels.get(channelId));
    }

    public List<String> aliases() {
        return List.copyOf(byAlias.keySet());
    }
}
