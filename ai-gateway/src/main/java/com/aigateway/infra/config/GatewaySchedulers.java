package com.aigateway.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 全局定时器线程池（脚手架，代码段 S7a）：
 * 超时关闭流、状态管道聚合都用它；任务必须轻量（关闭流/读队列）。
 */
@Configuration
public class GatewaySchedulers {

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService gatewayScheduler() {
        return Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "gw-timer");
            t.setDaemon(true);
            return t;
        });
    }
}
