package com.aigateway.config.loader;

import java.util.Map;

import com.aigateway.governance.admin.AdminProperties;
import com.aigateway.governance.config.GovernanceProperties;
import com.aigateway.infra.config.GatewayProperties;

public record ParsedConfig(
    Map<String, Object> root,            // 合并后的gateway节点
    String yaml,                        // 合并后的YAML原文
    GatewayProperties props,
    GovernanceProperties governance,
    AdminProperties admin
) {
    
}
