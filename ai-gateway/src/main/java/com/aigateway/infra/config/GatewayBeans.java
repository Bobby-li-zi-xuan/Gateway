package com.aigateway.infra.config;

import com.aigateway.decision.state.StateEvent;
import com.aigateway.decision.state.StateEventPipeline;
import com.aigateway.execution.cooldown.CooldownManager;
import com.aigateway.execution.policy.ExecutionPolicyManager;
import com.aigateway.observability.GatewayMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * V3 装配类（脚手架接线，详细实施计划 11.2 / 13.3）：
 * - CooldownManager（H4）：接线层把冷却进出转成状态事件 + 指标（回调必须轻量、不抛异常）。
 * - MeteringCallback：V4 起由 TokenMeter（H4，@Component）提供实现，此处不再定义缺省 Bean。
 */
@Configuration
public class GatewayBeans {

    /** 冷却管理器 Bean：Listener 回调只做"入队 + 原子计数"两类轻量动作（compute 回调内执行） */
    @Bean
    public CooldownManager cooldownManager(ExecutionPolicyManager policyManager,
                                           StateEventPipeline stateEvents, GatewayMetrics metrics) {
        CooldownManager.Listener listener = (channelId, cooling, cooldownMs) -> {
            stateEvents.offer(new StateEvent.CooldownChange(channelId, cooling, cooldownMs));
            metrics.cooldownEvent(channelId, cooling ? "enter" : "exit");
        };
        return new CooldownManager(policyManager.defaults().cooldown(), listener);
    }
}
