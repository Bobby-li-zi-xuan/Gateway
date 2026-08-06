package com.aigateway.plugin.registry;

import com.aigateway.core.exception.GatewayException;
import com.aigateway.infra.config.GatewayProperties;
import com.aigateway.plugin.spi.GatewayPlugin;
import com.aigateway.plugin.spi.PluginScope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 插件注册表：扫描 Spring 容器里的 {@link GatewayPlugin} bean，
 * 按 gateway.plugins 配置组装 {@link PluginRegistration}。
 *
 * 启动校验（对照学习版文档 4.1）：
 * - 配置引用的插件名必须存在（否则启动失败，避免运行时才发现）；
 * - 同名插件不允许重复挂载（去重）；
 * - 每个插件先 validate(config) 再 configure(config)；
 * - 配置不符合 Schema 时抛 GatewayException，导致启动失败。
 */
@Component
public class PluginRegistry {

    private final List<PluginRegistration> registrations;

    public PluginRegistry(GatewayProperties props, List<GatewayPlugin> plugins) {
        Map<String, GatewayPlugin> byName = plugins.stream()
                .collect(Collectors.toMap(GatewayPlugin::name, Function.identity(),
                        (a, b) -> {
                            throw new IllegalStateException("插件名重复: " + a.name());
                        }));

        Set<String> seen = new HashSet<>();
        List<PluginRegistration> list = new ArrayList<>();
        for (GatewayProperties.PluginDef def : props.getPlugins()) {
            GatewayPlugin plugin = byName.get(def.getName());
            if (plugin == null) {
                throw new GatewayException(500, "invalid_config",
                        "配置引用了不存在的插件: " + def.getName());
            }
            if (!seen.add(def.getName())) {
                throw new GatewayException(500, "invalid_config",
                        "插件重复挂载（同名去重）: " + def.getName());
            }
            plugin.validate(def.getConfig());   // 插件自检 Schema（不合法抛异常）
            plugin.configure(def.getConfig());  // 应用 YAML 里的 config
            list.add(new PluginRegistration(def.getName(),
                    PluginScope.valueOf(def.getScope().toUpperCase()),
                    def.getScopeValue(), def.getOrder(), Map.copyOf(def.getConfig()), plugin));
        }
        this.registrations = List.copyOf(list);
    }

    /** 全部注册项（运行期只读快照） */
    public List<PluginRegistration> all() {
        return registrations;
    }
}
