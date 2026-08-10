package com.aigateway.plugin.spi;

import com.aigateway.plugin.context.PluginContext;

import java.util.Map;

/**
 * 插件契约（SPI）：所有横切能力（鉴权、限流、护栏、缓存、审计……）以插件形式挂载。
 *
 * 设计动机（对照学习版文档 4.1）：
 * - 加一个能力 = 写一个类 + 改配置，核心路由代码零改动；
 * - 五个钩子对应请求生命周期关键点，后置钩子能看到前置钩子写入的信号与收窄后的候选。
 *
 * 生命周期钩子（Kong phases 的映射）：
 * - beforeDecision：决策前，写信号 / 收窄候选；
 * - afterDecision：决策后，只读 decision（可写审计类信号）；
 * - beforeExecution / afterExecution：执行前后；
 * - onError：异常时。
 */
public interface GatewayPlugin {

    /** 插件唯一名（与配置 plugins[].name 对应） */
    String name();

    /** 声明式作用域：GLOBAL / ROUTE / MODEL（配合 PluginRegistration.matches 精确匹配） */
    default PluginScope scope() { return PluginScope.GLOBAL; }

    /** 同阶段内的执行顺序（Kong priority 思想：数字大的先执行） */
    default int order() { return 0; }

    /** 启动校验：配置不符合 Schema 时抛 GatewayException，导致启动失败 */
    default void validate(Map<String, Object> config) {}

    /** 启动时由注册表调用一次：把 YAML 配置应用到实例 */
    default void configure(Map<String, Object> config) {}

    /** 路由决策前，按条件收窄候选模型池 */
    default void beforeDecision(PluginContext ctx) {}

    /** 决策完成后，观察/审计最终的模型 */
    default void afterDecision(PluginContext ctx) {}

    /** 调用上游模型前，改写请求体，注入上下文 */
    default void beforeExecution(PluginContext ctx) {}

    /** 上游响应后，记录信号，质量评分 */
    default void afterExecution(PluginContext ctx) {}

    /** 任意一处出错，拿到错误信息 */
    default void onError(PluginContext ctx, Throwable t) {}
}
