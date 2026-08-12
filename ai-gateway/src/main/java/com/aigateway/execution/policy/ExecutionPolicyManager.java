package com.aigateway.execution.policy;

import com.aigateway.core.exception.GatewayException;
import com.aigateway.execution.model.CircuitBreakerConfig;
import com.aigateway.execution.model.CooldownConfig;
import com.aigateway.execution.model.ExecutionPolicies;
import com.aigateway.execution.model.RetryPolicy;
import com.aigateway.execution.model.TimeoutPolicy;
import com.aigateway.infra.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 运行期策略合并与启动校验（脚手架）：
 * - 启动期把 {@link GatewayProperties.Execution} 展开为不可变快照 {@link ExecutionPolicies}，
 *   并校验全部容错参数（非法参数启动失败，而不是线上 5xx）；
 * - 每次请求按 "缺省 → 渠道覆盖 → header 覆盖" 三层合并出最终策略；
 * - 本类只做合并与校验，不持有任何可变状态，天然线程安全。
 */
@Component
public class ExecutionPolicyManager {

    private final GatewayProperties props;
    private final ExecutionPolicies defaults;

    public ExecutionPolicyManager(GatewayProperties props) {
        this.props = props;
        this.defaults = buildDefaults(props.getExecution());
        validate(defaults);   // 启动期尽早失败：容错参数非法比线上 5xx 好一万倍
    }

    /** 请求级策略：缺省 → 渠道覆盖 → header 覆盖 */
    public ExecutionPolicies forRequest(String channelId, Map<String, String> metadata) {
        GatewayProperties.TimeoutDef channelTimeout = props.getChannels().stream()
                .filter(c -> c.getId().equals(channelId))
                .map(GatewayProperties.ChannelDef::getTimeout)
                .filter(Objects::nonNull)
                .findFirst().orElse(null);

        long totalMs = parseHeader(metadata, "x-gateway-timeout-ms", defaults.totalMs());
        long idleMs = parseHeader(metadata, "x-gateway-idle-timeout-ms",
                channelTimeout != null ? channelTimeout.getIdleMs() : defaults.timeout().idleMs());

        TimeoutPolicy timeout = new TimeoutPolicy(
                channelTimeout != null ? channelTimeout.getConnectMs() : defaults.timeout().connectMs(),
                channelTimeout != null ? channelTimeout.getRequestMs() : defaults.timeout().requestMs(),
                channelTimeout != null ? channelTimeout.getFirstByteMs() : defaults.timeout().firstByteMs(),
                idleMs);
        return new ExecutionPolicies(totalMs, timeout,
                defaults.retry(), defaults.circuitBreaker(), defaults.cooldown());
    }

    /** 请求级 header 解析：非正整数 → 400 invalid_request（显式契约，解析失败不能静默回退） */
    private static long parseHeader(Map<String, String> metadata, String key, long fallback) {
        String raw = metadata == null ? null : metadata.get(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            long value = Long.parseLong(raw.trim());
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new GatewayException(400, "invalid_request",
                    "请求级超时 header 必须是正整数: " + key + "=" + raw);
        }
    }

    /** 启动校验规则：所有容错参数必须在合理范围内，否则启动失败 */
    private void validate(ExecutionPolicies p) {
        require(p.totalMs() > 0, "execution.totalMs 必须 > 0");
        require(p.timeout().connectMs() > 0 && p.timeout().requestMs() > 0
                        && p.timeout().firstByteMs() > 0 && p.timeout().idleMs() > 0,
                "execution.defaultTimeout 各层必须 > 0");
        require(p.retry().maxAttemptsPerCandidate() >= 1, "maxAttemptsPerCandidate 必须 >= 1");
        require(p.retry().jitterRatio() >= 0 && p.retry().jitterRatio() <= 0.5,
                "jitterRatio 必须在 [0, 0.5]");
        require(p.retry().backoffBaseMs() <= p.retry().backoffMaxMs(),
                "backoffBaseMs 不能大于 backoffMaxMs");
        require(!p.retry().retryableStatuses().isEmpty(), "retryableStatuses 不能为空");
        require(p.circuitBreaker().windowSize() >= 10, "windowSize 至少 10");
        require(p.circuitBreaker().minimumRequests() >= 1
                        && p.circuitBreaker().minimumRequests() <= p.circuitBreaker().windowSize(),
                "minimumRequests 必须在 [1, windowSize]");
        require(p.circuitBreaker().failureRateThreshold() > 0
                        && p.circuitBreaker().failureRateThreshold() <= 1,
                "failureRateThreshold 必须在 (0, 1]");
        require(p.circuitBreaker().slowCallRateThreshold() > 0
                        && p.circuitBreaker().slowCallRateThreshold() <= 1,
                "slowCallRateThreshold 必须在 (0, 1]");
        require(p.circuitBreaker().openDurationMs() > 0, "openDurationMs 必须 > 0");
        require(p.circuitBreaker().halfOpenMaxRequests() >= 1, "halfOpenMaxRequests 必须 >= 1");
        require(p.cooldown().consecutiveFailures() >= 1, "consecutiveFailures 必须 >= 1");
        require(p.cooldown().cooldownMs() > 0, "cooldownMs 必须 > 0");
        require(p.cooldown().maxCooldownMs() >= p.cooldown().cooldownMs(),
                "maxCooldownMs 不能小于 cooldownMs");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new GatewayException(500, "invalid_config", message);
    }

    /** 缺省策略快照（熔断 / 冷却等单例 Bean 装配时取用） */
    public ExecutionPolicies defaults() { return defaults; }

    private static ExecutionPolicies buildDefaults(GatewayProperties.Execution e) {
        return new ExecutionPolicies(
                e.getTotalMs(),
                new TimeoutPolicy(e.getDefaultTimeout().getConnectMs(),
                        e.getDefaultTimeout().getRequestMs(),
                        e.getDefaultTimeout().getFirstByteMs(),
                        e.getDefaultTimeout().getIdleMs()),
                new RetryPolicy(e.getRetry().getMaxAttemptsPerCandidate(),
                        e.getRetry().isSameInstanceOnConnectionFailure(),
                        e.getRetry().isRespectRetryAfter(),
                        e.getRetry().getMaxRetryAfterMs(),
                        e.getRetry().getBackoffBaseMs(),
                        e.getRetry().getBackoffMaxMs(),
                        e.getRetry().getJitterRatio(),
                        Set.copyOf(e.getRetry().getRetryableStatuses())),
                new CircuitBreakerConfig(e.getCircuitBreaker().getWindowSize(),
                        e.getCircuitBreaker().getMinimumRequests(),
                        e.getCircuitBreaker().getFailureRateThreshold(),
                        e.getCircuitBreaker().getSlowCallThresholdMs(),
                        e.getCircuitBreaker().getSlowCallRateThreshold(),
                        e.getCircuitBreaker().getOpenDurationMs(),
                        e.getCircuitBreaker().getHalfOpenMaxRequests()),
                new CooldownConfig(e.getCooldown().getConsecutiveFailures(),
                        e.getCooldown().getCooldownMs(),
                        e.getCooldown().getMaxCooldownMs(),
                        e.getCooldown().isRecoveryProbe()));
    }
}
