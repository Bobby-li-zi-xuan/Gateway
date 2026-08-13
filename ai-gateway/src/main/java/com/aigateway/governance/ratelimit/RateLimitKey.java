package com.aigateway.governance.ratelimit;

/** 限流维度 + key：同一维度同一 key 共享一个滑动窗口桶 */
public record RateLimitKey(String dimension, String key) {
    public static RateLimitKey token(String tokenId)  { return new RateLimitKey("token", tokenId); }
    public static RateLimitKey model(String alias)    { return new RateLimitKey("model", alias); }
    public static RateLimitKey channel(String chId)   { return new RateLimitKey("channel", chId); }
    public static RateLimitKey global()               { return new RateLimitKey("global", ""); }
}
