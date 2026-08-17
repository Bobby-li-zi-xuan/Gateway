package com.aigateway.config.model;

import com.aigateway.governance.admin.AdminProperties;
import com.aigateway.governance.config.GovernanceProperties;
import com.aigateway.infra.config.GatewayProperties;

/**
 * 不可变配置快照（copy-on-write 的"写"单位）。
 * 创建后不再修改；组件只持有引用读取。每次热更新产生新快照并原子替换全局引用。
 */
public record GatewayConfig(
    int version,                //版本号
    String note,                //变更说明（热更新时传入）
    long createdAt,             //epoch毫秒
    String yaml,                //合并后的YAML原文
    GatewayProperties props,
    GovernanceProperties governance,        // tokens 之外的全部治理参数
    AdminProperties admin
){}