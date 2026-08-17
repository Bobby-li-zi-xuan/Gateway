package com.aigateway.governance.ratelimit;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 滑动窗口限流器：按 key（令牌/模型/渠道/全局）独立计数（🖊 H2：逻辑全部手敲，对照 9.2）。
 *
 * 两种模式（学习版 4.4 的双层治理第一半）：
 * - 普通模式：cost = 1，限制请求次数（QPS / RPM）；
 * - tokenMode：cost = 预估输入 token，请求结束时用实际用量 adjust 修正——
 *   解决“流式请求结束前不知道总量”的问题（预扣占位 + 结算修正）。
 *
 * 学习要点：
 * - 环形子桶滑动窗口：窗口和 O(1) 可算，旧样本随滑窗自动清零；
 * - synchronized 锁粒度 = 单个桶（每 key），多 key 互不阻塞；
 * - adjust 可为负：结算时用“实际 - 预估”回补，保证长期口径正确；
 * - 拒绝不计数：被拒请求没进窗口，窗口只统计真正放行的那部分。
 */
@Component
public class RateLimiter {

    private static final int SLOTS = 10;   // 子桶数：窗口被切成 10 段
    private final Map<BucketKey, Bucket> buckets = new ConcurrentHashMap<>();

    /** 桶的定位键：同 key 不同窗口（QPS 与 RPM）是独立桶 */
    private record BucketKey(RateLimitKey key, long windowMs) {}

    /** 一个滑动窗口桶：环形计数 + 惰性滑窗 */
    private static final class Bucket {
        final long windowMs;
        final long limit;
        final long[] counts = new long[SLOTS];
        int currentSlot;                 // 当前子桶下标
        long currentSlotStartMs;         // 当前子桶的开始时刻

        Bucket(long windowMs, long limit) {
            this.windowMs = windowMs;
            this.limit = limit;
            this.currentSlotStartMs = System.currentTimeMillis();
        }

        /** 窗口内已放行的总量（10 个槽求和） */
        long windowSum() {
            long sum = 0;
            for (long c : counts) sum += c;
            return sum;
        }
    }

    /**
     * 尝试放行：cost = 请求数（1）或预估 token 数。
     * @return true 放行；false 超限（调用方回 429）
     */
    public boolean tryAcquire(RateLimitRule rule, long cost) {
        Bucket bucket = buckets.computeIfAbsent(
                new BucketKey(rule.key(), rule.windowMs()),
                k -> new Bucket(rule.windowMs(), rule.limit()));
        synchronized(bucket){
            slide(bucket, System.currentTimeMillis());
            // cost超过窗口直接快速失败
            if(cost > bucket.limit) return false;

            long sum = bucket.windowSum();
            if(sum + cost > bucket.limit){
                return false;
            }
            bucket.counts[bucket.currentSlot] += cost;
            return true;
        }      
    }

    /**
     * 结算修正：请求结束后用「实际 - 预估」回补（tokenMode 专用）。
     * delta 为负 = 实际少于预估（占位过多，回吐）；为正 = 实际超出预估（补记）。
     */
    public void adjust(RateLimitKey key, long windowMs, long delta) {
        Bucket bucket = buckets.get(new BucketKey(key, windowMs));
        if(bucket == null) return;

        synchronized(bucket){
            slide(bucket, System.currentTimeMillis());
            // 只修正当前槽，跨槽误差在窗口尺度上自动弥合
            bucket.counts[bucket.currentSlot] = Math.max(0,
                    bucket.counts[bucket.currentSlot] + delta
            );
        }
    }

    /** 惰性滑窗：时间前进了 k 个子桶 → 途经槽清零，指针前移（旧样本过期） */
    private static void slide(Bucket bucket, long nowMs) {
        // 时间差
        long elapsed = nowMs - bucket.currentSlotStartMs;
        // 滑过多少个子桶
        long step = elapsed / (bucket.windowMs / SLOTS);
        if(step <= 0) return;
        if(step >= SLOTS){
            // 一整个窗口都过去了，全部清零
            Arrays.fill(bucket.counts, 0);
            bucket.currentSlot = 0;
        }else{
            for(int i = 1; i <= step; i ++){
                int idx = (bucket.currentSlot + i) % SLOTS;
                bucket.counts[idx] = 0;
            }
            bucket.currentSlot = (int) ((bucket.currentSlot + step) % SLOTS);
        }
        //nowMs - 零头(从当前时刻倒退零头毫秒 = 上一个完整子桶的起点)
        //零头:跨过完整子桶后剩下的不足一个子桶的时间
        bucket.currentSlotStartMs = nowMs - (elapsed % (bucket.windowMs / SLOTS));
    }

    /** 当前窗口内的累计值（测试/指标用；线程安全：synchronized 读） */
    public long windowCount(RateLimitKey key, long windowMs) {
        Bucket bucket = buckets.get(new BucketKey(key, windowMs));
        if (bucket == null) return 0;
        synchronized (bucket) {
            slide(bucket, System.currentTimeMillis());
            return bucket.windowSum();
        }
    }
}
