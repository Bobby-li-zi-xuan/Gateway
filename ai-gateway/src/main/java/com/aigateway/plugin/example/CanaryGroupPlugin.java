package com.aigateway.plugin.example;

import com.aigateway.core.exception.GatewayException;
import com.aigateway.plugin.context.PluginContext;
import com.aigateway.plugin.spi.GatewayPlugin;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 示例插件：金丝雀分组。
 *
 * 职责：按请求头 X-Canary-Group（由 Controller 写入 metadata）写
 * {@code canary_group} 与 {@code allowed_instances} 信号，
 * 由 Filter Chain 第 5 道（PluginSignalFilter）统一消费。
 *
 * 语义：stable 组只进稳定实例；experimental 组只进灰度实例；
 * 未配置的实例一律不可达（金丝雀流量只进配置允许的候选集合）。
 */
@Component
public class CanaryGroupPlugin implements GatewayPlugin {

    private Set<String> stableInstances = Set.of();
    private Set<String> canaryInstances = Set.of();

    public String name() {
        return "canary";
    }

    public int order() {
        return 20;
    }

    public void validate(Map<String, Object> config) {
        if (config.get("stableInstances") == null || config.get("canaryInstances") == null) {
            throw new GatewayException(500, "invalid_config",
                    "canary 插件需要配置 stableInstances 与 canaryInstances");
        }
    }

    public void configure(Map<String, Object> config) {
        stableInstances = toStringSet(config.get("stableInstances"));
        canaryInstances = toStringSet(config.get("canaryInstances"));
    }

    public void beforeDecision(PluginContext ctx) {
        String group = ctx.metadata().getOrDefault("canary_group", "stable");
        ctx.signals().set("canary_group", group);
        Set<String> allowed = "stable".equals(group) ? stableInstances : canaryInstances;
        ctx.signals().set("allowed_instances", allowed);
    }

    private static Set<String> toStringSet(Object value) {
        if (value == null) {
            return Set.of(); // null = 该分组未配置任何实例（配合白名单“存在但为空 → 全淘汰”语义）
        }
        if (value instanceof Map<?, ?> map) {
            // Spring Boot 把 YAML list 绑定进 Map<String,Object> 时变成 index-keyed Map
            //（{0=a, 1=b} 而非 List），按值转集合与 List 语义等价
            return map.values().stream().map(String::valueOf).collect(Collectors.toSet());
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.toSet());
        }
        return Set.of(String.valueOf(value));
    }
}
