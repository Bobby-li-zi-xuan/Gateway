package com.aigateway.execution.cooldown;

import com.aigateway.execution.model.CooldownConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渠道冷却：连续失败达阈值 → 冷却；到期后第一个请求视为"恢复探测"。
 *
 * 要点：
 * - ConcurrentHashMap.compute 原子更新，并发记录不丢计数；
 * - isCooling() 顺带做"到期 → 进入探测期"的惰性状态迁移，迁移必须走 compute 原子完成，
 *   不能在 get() 返回的裸对象上写字段（否则与 recordFailure 的 compute 竞态 TOCTOU）；
 * - 冷却进出通过 Listener 回调通知接线层，回调必须轻量且不抛异常；
 * - 恢复探测失败：时长翻倍（上限 maxCooldownMs）；成功：清零并恢复基准时长。
 */
public final class CooldownManager {

    /** 冷却状态变化回调：@param cooling true=进入冷却；false=冷却结束/恢复（含转入探测期） */
    @FunctionalInterface
    public interface Listener {
        void onCooldownChange(String channelId, boolean cooling, long cooldownMs);
    }

    // 冷却策略：连续失败阈值、基准时长、翻倍上限、探测开关
    private final CooldownConfig config;   
    // 冷却进出回调：通知接线层发事件/指标，必须轻量且不抛异常
    private final Listener listener;
    // channelId → 冷却状态
    private final Map<String, ChannelCooldown> states = new ConcurrentHashMap<>();

    private static final class ChannelCooldown {
        int consecutiveFails;    // 连续失败次数，达标触发冷却
        long coolingUntilMs;     // 冷却到期时间戳（0 = 不在冷却）
        long currentCooldownMs;  // 当前冷却时长，恢复探测失败时翻倍（上限 maxCooldownMs）
        boolean pendingProbe;    // 冷却到期后置为探测期：此期间下一个失败直接翻倍重新冷却
    }

    public CooldownManager(CooldownConfig config) {
        this(config, (channelId, cooling, cooldownMs) -> {});
    }

    public CooldownManager(CooldownConfig config, Listener listener) {
        this.config = config;
        this.listener = listener;
    }

    /** 渠道是否在冷却期（执行前调用）；到期会自动转入探测期
     *  isCooling() 不只是查状态，还顺带做状态迁移（"惰性"：不专门跑定时器，而是每次被调用时检查是否到期）：
        1. 读当前状态；不存在或本来就不在冷却 → 原样返回，不改变
        2. 若冷却已到期（now >= coolingUntilMs）→ 就地改对象字段：清零冷却时间、置 pendingProbe 进入探测期、通知监听器
        3. 返回 s.coolingUntilMs > 0，即"现在是否还在冷却中"
     */
    public boolean isCooling(String channelId) {
        // 惰性迁移必须用 compute：裸写get（）返回的对象会与recordFailure的compute竞态
        ChannelCooldown s = states.compute(channelId, (id, old) ->{
            if(old == null || old.coolingUntilMs == 0) return old;
            if(System.currentTimeMillis() >= old.coolingUntilMs){
                old.coolingUntilMs = 0;    //冷却结束
                old.pendingProbe = config.recoveryProbe();      //进入探测期
                listener.onCooldownChange(id, false, 0);
            }
            return old;
        });
        return s != null && s.coolingUntilMs > 0;
    }

    /** 记录一次失败：连续失败达标 → 冷却；探测期失败 → 翻倍冷却 */
    public void recordFailure(String channelId) {
        states.compute(channelId, (id, old) -> {
            ChannelCooldown s = old != null ? old : new ChannelCooldown();
            if(s.coolingUntilMs != 0) return s;     //已经在冷却期；不再累计

            if(s.pendingProbe){
                // 恢复探测失败：重新冷却，时长翻倍（有上限）
                long next = Math.min(Math.max(s.currentCooldownMs * 2, config.cooldownMs()), config.maxCooldownMs());
                enterCooldown(s, next);
                s.consecutiveFails = 1;
                s.pendingProbe = false;
                listener.onCooldownChange(channelId, true, next);
                return s;
            }
            s.consecutiveFails++;
            if (s.consecutiveFails >= config.consecutiveFailures()) {
                enterCooldown(s, config.cooldownMs());
                s.consecutiveFails = 0;
                listener.onCooldownChange(channelId, true, config.cooldownMs());
            }
            return s;
        });
    }

    /** 记录一次成功：清零计数、退出探测期、恢复基准冷却时长 */
    public void recordSuccess(String channelId) {
        states.compute(channelId, (id, old) -> {
            if(old == null) return null;
            boolean wasCooling = old.coolingUntilMs != 0;
            old.consecutiveFails = 0;
            old.pendingProbe = false;
            old.coolingUntilMs = 0;
            old.currentCooldownMs = config.cooldownMs();
            if(wasCooling) listener.onCooldownChange(id, false, 0);
            return old;
        });
    }
    //进入冷却期并设置时间
    private void enterCooldown(ChannelCooldown s, long durationMs) {
        s.currentCooldownMs = durationMs;
        s.coolingUntilMs = System.currentTimeMillis() + durationMs;
    }
}
