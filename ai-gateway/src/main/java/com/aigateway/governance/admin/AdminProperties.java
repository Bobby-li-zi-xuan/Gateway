package com.aigateway.governance.admin;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** gateway.admin 配置段：管理面参数（V5，由 ConfigLoader 绑定）。 */
@Data
public class AdminProperties {
    private String name = "admin";          // 审计操作者名称
    private String apiKey = "";             // 管理密钥：env: 引用或明文；空 = 启动生成随机
    private List<String> ipWhitelist = new ArrayList<>(); // 空 = 不限
}
