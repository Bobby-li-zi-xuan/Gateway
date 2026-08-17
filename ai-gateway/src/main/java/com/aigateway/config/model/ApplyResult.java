package com.aigateway.config.model;

import java.util.List;

/** 热更新结果：成功携带新快照与警告：失败携带错误列表（当前配置保持不变） */
public record ApplyResult(
    boolean ok,
    GatewayConfig config,       // 成功时的新快照：失败为null
    List<ConfigError> errors,    // 失败原因（校验 / 预检）
    List<String> warnings
){
    // 专门的成功状态实例，成功时的非阻断提示
    public static ApplyResult success(GatewayConfig config, List<String> warnings){
        return new ApplyResult(true, config, List.of(), warnings);
    }

    // 专门的失败状态实例
    public static ApplyResult failed(List<ConfigError> errors){
        return new ApplyResult(false, null, errors, List.of());
    }
}
 
