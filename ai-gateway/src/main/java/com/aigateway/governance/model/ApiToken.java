package com.aigateway.governance.model;

/**
 * 令牌领域对象（对应 New API 的 ApiToken）：
 * - 明文凭证只在创建时出现一次，库里只存 tokenHash（SHA-256 十六进制）；
 * - quotaLimit 是「总量额度」：COST 单位为美元、TOKEN 单位为 token 数；-1 = 无限；
 * - 与 Budget（周期配额）的区别：令牌额度不按周期重置，花完即止（New API 语义）。
 */
public record ApiToken(
        String id,             // 管理 ID（前缀 agw_ + 8 hex，如 agw_3f9a2b1c）
        String name,           // 显示名（脱敏列表用）
        String tokenHash,      // SHA-256(明文 token) 十六进制，唯一索引
        double quotaLimit,     // 额度上限；-1 = 无限
        String quotaType,      // COST / TOKEN
        String modelScope,     // 逗号分隔的 alias；空 = 不限
        String ipWhitelist,    // 逗号分隔的 IP；空 = 不限
        long expiresAt,        // epoch 毫秒；-1 = 永不过期
        boolean enabled,
        long createdAt
) {
    /** 是否无限额度 */
    public boolean unlimited() { return quotaLimit < 0; }
}
