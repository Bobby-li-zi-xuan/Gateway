package com.aigateway.plugin.engine;

import com.aigateway.core.exception.GatewayException;
import com.aigateway.plugin.context.PluginContext;
import com.aigateway.plugin.registry.PluginRegistration;
import com.aigateway.plugin.registry.PluginRegistry;
import com.aigateway.plugin.spi.GatewayPlugin;
import com.aigateway.plugin.spi.PluginPhase;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 插件执行引擎：把注册表里的插件按“阶段 → 作用域 → order”组织成链，逐条执行。
 *
 * 需要实现的关键行为（对照 LiteLLM Router Plugins）：
 * 1. 过滤出当前上下文匹配的插件（GLOBAL 全量 / ROUTE 按别名 / MODEL 按候选）；
 * 2. 按 order 降序排序（Kong priority：数字大的先执行）；
 * 3. 逐个执行；每个插件都能看到前一个插件写入的信号与收窄后的候选；
 * 4. 决策前阶段执行 fail-closed：某插件清空候选池 → 503 candidates_cleared，
 *    且立即终止（后面的插件不再执行，策略链不可被绕过）；
 * 5. 插件异常统一包装为 500 plugin_error。
 */
@Component
public class PluginEngine {

    // 启动时快照，运行期只读
    private final List<PluginRegistration> registrations; 

    public PluginEngine(PluginRegistry registry) {
        this.registrations = registry.all();
    }

    /**
     * 执行某个生命周期阶段。
     *
     * 顺序语义：
     * 1. 过滤出当前上下文匹配的插件（GLOBAL 全量 / ROUTE 按别名 / MODEL 按候选）；
     * 2. 按 order 降序排序（Kong priority：数字大的先执行）；
     * 3. 逐个执行；每个插件都能看到前一个插件写入的信号与收窄后的候选；
     * 4. 决策前阶段执行 fail-closed：某插件清空候选池 → 503 candidates_cleared，
     *    且立即终止（后面的插件不再执行，策略链不可被绕过）。
     */
    public void runPhase(PluginPhase phase, PluginContext ctx) {
        List<PluginRegistration> chain = registrations.stream()
                .filter(r -> r.matches(ctx))
                .sorted(Comparator.comparingInt(PluginRegistration::order).reversed())
                .toList();

        for(PluginRegistration reg : chain){
            try{
                invoke(reg.plugin(), phase, ctx);
            }catch (GatewayException e) {
                throw e; // 业务异常原样上抛（如插件主动拒绝请求）
            } catch (Exception e) {
                throw new GatewayException(500, "plugin_error",
                        "插件[" + reg.name() + "]在" + phase + "阶段执行失败: " + e.getMessage(), e);
            }

            // fail-closed：只对“决策前”的候选收窄语义生效
            if (phase == PluginPhase.BEFORE_DECISION && ctx.candidates().isEmpty()) {
                throw new GatewayException(503, "candidates_cleared",
                        "插件[" + reg.name() + "]在" + phase + "阶段清空了候选池");
            }
        }
    }

    private void invoke(GatewayPlugin plugin, PluginPhase phase, PluginContext ctx) {
        switch (phase) {
            case BEFORE_DECISION -> plugin.beforeDecision(ctx);
            case AFTER_DECISION -> plugin.afterDecision(ctx);
            case BEFORE_EXECUTION -> plugin.beforeExecution(ctx);
            case AFTER_EXECUTION -> plugin.afterExecution(ctx);
            case ON_ERROR -> plugin.onError(ctx, ctx.error());
        }
    }
}
