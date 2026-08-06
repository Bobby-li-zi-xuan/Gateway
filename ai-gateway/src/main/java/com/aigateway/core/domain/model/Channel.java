package com.aigateway.core.domain.model;

/**
 * 渠道：一个上游端点（供应商账号 / 自建服务）。
 *
 * 例如 model.yml 里的 mock-a（http://localhost:8001）。一个渠道下可以有多个模型，
 * 一个模型别名也可以跨渠道配置多个候选（用于容灾/分流）。
 *
 * 学习要点：
 * - record 不可变，天然线程安全，适合配置加载后只读的场景；
 * - weight 是渠道级权重，参与后续的加权随机路由（H1）。
 */
public record Channel(
        String id,              // 渠道 ID，全局唯一（如 mock-a）
        String provider,        // 供应商类型（如 openai-compatible）
        String baseUrl,         // 上游基础地址，拼接 /v1/chat/completions 后调用
        String credentialsRef,  // 密钥引用（"env:XXX"），配置里不出现明文
        int weight              // 渠道权重：同一别名下所有候选按权重比例被选中
) {

    /** 只有配置了 credentialsRef 才需要在请求头里带 Authorization */
    public boolean needAuth() {
        return credentialsRef != null && !credentialsRef.isBlank();
    }
}
