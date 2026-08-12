package com.aigateway.execution.model;

import java.util.Set;

/**
 * 重试配置（学习版 4.1）：全部可配置，对应 LiteLLM 的 num_retries / fallbacks 声明式思路。
 *
 * @param maxAttemptsPerCandidate            每个候选默认尝试次数（默认 1 = 不重试同实例）
 * @param sameInstanceOnConnectionFailure    连接级失败允许同实例重试一次
 * @param respectRetryAfter                  429 优先遵循上游 Retry-After
 * @param maxRetryAfterMs                    Retry-After 等待上限，防止被上游拖死
 * @param backoffBaseMs                      指数退避基数
 * @param backoffMaxMs                       退避上限
 * @param jitterRatio                        随机抖动比例 0~0.5（防惊群）
 * @param retryableStatuses                  可重试/可降级的上游状态码
 */
public record RetryPolicy(
        int maxAttemptsPerCandidate,
        boolean sameInstanceOnConnectionFailure,
        boolean respectRetryAfter,
        long maxRetryAfterMs,
        long backoffBaseMs,
        long backoffMaxMs,
        double jitterRatio,
        Set<Integer> retryableStatuses
) {}
