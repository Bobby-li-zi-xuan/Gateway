package com.aigateway;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 集成测试专用网关启动类：显式收窄组件扫描范围。
 *
 * 为什么需要：生产 {@link GatewayApplication} 默认扫描 com.aigateway 全包；
 * 但集成测试的 classpath 上还有 mock 模块的类（test 依赖），其中
 * {@code com.aigateway.mock.model.ModelRegistry} 与 {@code com.aigateway.state.registry.ModelRegistry}
 * 同名，Spring 生成的默认 bean 名都是 modelRegistry，会冲突导致启动失败。
 * 这里只扫描网关自身的子包（与 mock 隔离）。
 *
 * V2 增量：新增 decision（决策引擎）与 plugin（插件框架）两个子包，
 * 否则脚手架 bean（PolicyManager / PluginRegistry / DecisionLogStore 等）不会被扫描注册。
 * V4 增量：新增 governance（令牌/限流/预算/计量/渠道/持久化）。
 */
@SpringBootApplication(scanBasePackages = {
        "com.aigateway.api",
        "com.aigateway.core",
        "com.aigateway.decision",
        "com.aigateway.infra",
        "com.aigateway.plugin",
        "com.aigateway.state",
        "com.aigateway.execution",
        "com.aigateway.observability",
        "com.aigateway.governance"
})
public class TestGatewayApplication {
}
