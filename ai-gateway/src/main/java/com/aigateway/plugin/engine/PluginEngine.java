package com.aigateway.plugin.engine;

import com.aigateway.core.exception.GatewayException;
import com.aigateway.plugin.context.PluginContext;
import com.aigateway.plugin.registry.PluginRegistry;
import com.aigateway.plugin.spi.PluginPhase;
import org.springframework.stereotype.Component;

/**
 * 插件执行引擎：把注册表里的插件按“阶段 → 作用域 → order”组织成链，逐条执行。
 *
 * 🖊 手敲 H1：本类为必手敲模块，按《版本2-详细实施计划》第 8.5 节实现。
 * 未完成前调用 {@link #runPhase} 会直接报错。
 *
 * 需要实现的关键行为（对照 LiteLLM Router Plugins）：
 * 1. 过滤出当前上下文匹配的插件（GLOBAL 全量 / ROUTE 按别名 / MODEL 按候选）；
 * 2. 按 order 升序排序（Kong priority：数字小的先执行）；
 * 3. 逐个执行；每个插件都能看到前一个插件写入的信号与收窄后的候选；
 * 4. 决策前阶段执行 fail-closed：某插件清空候选池 → 503 candidates_cleared，
 *    且立即终止（后面的插件不再执行，策略链不可被绕过）；
 * 5. 插件异常统一包装为 500 plugin_error。
 */
@Component
public class PluginEngine {

    private final PluginRegistry registry;

    public PluginEngine(PluginRegistry registry) {
        this.registry = registry;
    }

    /** TODO H1：按《版本2-详细实施计划》第 8.5 节手敲实现 */
    public void runPhase(PluginPhase phase, PluginContext ctx) {
        throw new GatewayException(500, "not_implemented",
                "TODO H1：插件执行引擎未实现（按《版本2-详细实施计划》第 8.5 节手敲）");
    }
}
