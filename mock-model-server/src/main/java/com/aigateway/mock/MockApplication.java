package com.aigateway.mock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Mock 模型服务启动入口。
 *
 * 与网关模块共用一套 OpenAI 兼容协议，用来在本地模拟真实大模型：
 * - 可配置模型画像（--mock.model=qwen-large / qwen-small / deepseek-code）；
 * - 支持故障注入、延迟模拟、健康开关（通过 /mock/behavior 动态调整）。
 *
 * 学习要点：同一个 JAR 可以通过启动参数 --mock.model 变成不同“模型”，
 * 这正是演示多模型网关时不需要真调 OpenAI 的原因。
 */
@SpringBootApplication
public class MockApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockApplication.class, args);
    }
}
