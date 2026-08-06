package com.aigateway.plugin.spi;

/**
 * 插件生命周期阶段（Kong phases 的映射：access/header_filter/body_filter/log/error）。
 * 五个钩子：决策前、决策后、执行前、执行后、异常处理。
 */
public enum PluginPhase {
    BEFORE_DECISION,   // 决策前：写信号、收窄候选（fail-closed 语义在此阶段生效）
    AFTER_DECISION,    // 决策后：只读 decision
    BEFORE_EXECUTION,  // 执行前
    AFTER_EXECUTION,   // 执行后
    ON_ERROR           // 异常时（ctx.error() 携带根因）
}
