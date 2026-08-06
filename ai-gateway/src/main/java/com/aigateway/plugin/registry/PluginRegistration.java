package com.aigateway.plugin.registry;

import com.aigateway.plugin.context.PluginContext;
import com.aigateway.plugin.spi.GatewayPlugin;
import com.aigateway.plugin.spi.PluginScope;

import java.util.Map;

/**
 * 插件注册项：配置（scope/order/config）与插件实例的绑定。
 * 启动时由 {@link PluginRegistry} 组装，运行期只读。
 */
public record PluginRegistration(
        String name,
        PluginScope scope,
        String scopeValue,           // ROUTE=别名；MODEL=instanceId
        int order,
        Map<String, Object> config,  // 插件实例独立配置（启动时已应用）
        GatewayPlugin plugin
) {
    /** 作用域匹配：GLOBAL 全量；ROUTE 匹配当前别名；MODEL 匹配候选 instanceId */
    public boolean matches(PluginContext ctx) {
        return switch (scope) {
            case GLOBAL -> true;
            case ROUTE -> scopeValue.equals(ctx.alias());
            case MODEL -> ctx.candidateIds().contains(scopeValue);
        };
    }
}
