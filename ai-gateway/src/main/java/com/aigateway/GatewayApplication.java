package com.aigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI 网关启动入口。
 *
 * 学习要点：
 * - {@code @SpringBootApplication} 等价于 {@code @Configuration} +
 *   {@code @EnableAutoConfiguration} + {@code @ComponentScan}，Spring 会扫描本包及
 *   子包下的所有组件（Controller / Service / Component），并自动装配依赖。
 * - 虚拟线程由 application.yaml 的 {@code spring.threads.virtual.enabled=true} 开启：
 *   每个请求跑在独立的虚拟线程上，阻塞式代码也能支撑较高并发（本项目的学习重点之一）。
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        // 启动内嵌 Tomcat：加载配置（application.yaml + model.yml）、完成 Bean 初始化，
        // 期间会执行 ModelRegistry 的启动校验（H5），配置非法时在这里直接启动失败。
        SpringApplication.run(GatewayApplication.class, args);
    }
}
