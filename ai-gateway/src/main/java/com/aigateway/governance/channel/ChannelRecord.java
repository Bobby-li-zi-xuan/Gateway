package com.aigateway.governance.channel;

/**
 * 渠道档案（对应 channel 表 + 管理端点视图）：
 * - deleted = 软删除标记（有历史用量时保护性删除）；
 * - 运行时 CRUD 通过 withXxx 副本方法生成新记录（ConcurrentHashMap 原子替换，无半改状态）。
 */
public record ChannelRecord(
        String id,
        String provider,
        String baseUrl,
        String credentialsRef,   // 只存引用（env:XXX），值不落库
        int weight,
        boolean enabled,
        boolean deleted,
        long createdAt
) {
    public ChannelRecord withEnabled(boolean enabled) {
        return new ChannelRecord(id, provider, baseUrl, credentialsRef, weight, enabled, deleted, createdAt);
    }

    public ChannelRecord withWeight(int weight) {
        return new ChannelRecord(id, provider, baseUrl, credentialsRef, weight, enabled, deleted, createdAt);
    }

    public ChannelRecord withDeleted(boolean deleted) {
        return new ChannelRecord(id, provider, baseUrl, credentialsRef, weight, enabled, deleted, createdAt);
    }
}
