package com.aigateway.plugin.spi;

/**
 * 插件声明式作用域（对照学习版文档 4.1 / Kong 作用域模型）：
 * - GLOBAL：所有请求生效；
 * - ROUTE：只对指定别名（scopeValue）生效；
 * - MODEL：只对指定候选实例（scopeValue = instanceId）生效。
 *
 * 精确匹配由 {@link com.aigateway.plugin.registry.PluginRegistration#matches} 实现。
 */
public enum PluginScope {
    GLOBAL, ROUTE, MODEL
}
