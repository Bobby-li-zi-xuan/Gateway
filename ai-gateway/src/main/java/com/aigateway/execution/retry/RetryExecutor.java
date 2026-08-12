package com.aigateway.execution.retry;

import com.aigateway.execution.model.Failure;
import com.aigateway.execution.model.FailureType;
import com.aigateway.execution.model.RetryPolicy;
import com.aigateway.execution.model.UpstreamCallException;
import com.aigateway.execution.timeout.TimeoutGuard;
import com.aigateway.observability.GatewayMetrics;

import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ⚠️ 手敲 H1（详细实施计划第 8 节 S6）——本类全部逻辑需手敲，方法体当前抛 TODO 异常。
 *
 * 重试执行器：只处理"同实例重试"的决策，不负责换候选（降级链在外层）。
 *
 * 手敲要点（对照计划 8.2）：
 * - 同实例重试只有两种情形：连接级失败（CONNECTION，可配）与 429 + Retry-After（可配）；
 *   其余可重试失败（超时 / 5xx）原样上抛，由降级链执行器换下一个候选；
 * - 等待时长：429 优先听上游 Retry-After（上限 maxRetryAfterMs）；
 *   连接失败用指数退避 min(base × 2^n, max) + 随机抖动；
 * - 所有等待受 TimeoutGuard 总时长预算约束（sleepWithinBudget 返回 false 即放弃）；
 * - 安全边界：本类不知道"流式首字节"的存在，首字节语义由 StreamProxy（H6）更外层把关；
 * - 同实例最多重试一次（attemptNo >= 1 即停）。
 */
@Component
public class RetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(RetryExecutor.class);
    private final GatewayMetrics metrics;

    public RetryExecutor(GatewayMetrics metrics) {
        this.metrics = metrics;
    }

    /** 一次"候选调用"的抽象：返回结果或抛结构化失败 */
    public interface Attempt<T> {
        T run() throws UpstreamCallException;
    }

    /**
     * 执行一次候选调用，按策略决定是否同实例重试。
     *
     * @param instanceId 候选实例（指标打点用）
     * @return 成功结果
     * @throws UpstreamCallException 重试耗尽（或不可重试）时抛出原始失败
     */
    public <T> T execute(RetryPolicy policy, TimeoutGuard guard,
                         String instanceId, Attempt<T> attempt) {
        int attemptNo = 0;      // 0 = 首次调用； 1 = 第一次同实例重试
        while(true){
            try{
                return attempt.run();
            }catch(UpstreamCallException e){
                Failure f = e.failure();

                // 不可重试/非可同实例重试 -> 直接上抛，交给降级链换候选
                if(!shouldRetrySameInstance(policy, f, attemptNo)){
                    throw e;
                }

                long waitMs = waitMillis(policy, attemptNo, f);
                //预算检查：等待会超过整条链的总时长 -> 直接放弃
                if(!guard.sleepWithinBudget(waitMs)){
                    throw new UpstreamCallException(
                        new Failure(FailureType.TIMEOUT_TOTAL, "同实例重试等待超出总时长预算："
                            + instanceId, -1, true, -1),
                        e);
                }
                log(instanceId, "同实例重试", "attempt=" + attemptNo + " reason=" + f.type()
                        + " wait=" + waitMs + "ms");
                metrics.retry(instanceId, f.type().name());
                attemptNo++;
            }
        }
    }

    /**
     * 同实例重试的两种情形：
     * - 连接级失败：连接都没建立，重打一次是安全的（默认允许）；
     * - 429 + Retry-After：上游明确告诉你“等多久再试”（默认允许）。
     * 其余一律不重试同实例——重试 = 换候选，由外层完成。
     */
    private boolean shouldRetrySameInstance(RetryPolicy policy, Failure f, int attemptNo){
        if(attemptNo >= 1) return false;
        if(!f.retryable()) return false;
        if(f.type() == FailureType.CONNECTION){
            return policy.sameInstanceOnConnectionFailure();
        }    
        return f.type() == FailureType.HTTP_429
                && policy.respectRetryAfter()
                && f.retryAfterMs() > 0;
    } 

    /**
     * 等待时长：
     * - 429：优先听上游 Retry-After（受 maxRetryAfterMs 上限约束）；
     * - 连接失败：指数退避 base × 2^n（受 backoffMaxMs 上限）+ 随机抖动。
     */
    private long waitMillis(RetryPolicy policy, int attemptNo, Failure f){
        if(f.type() == FailureType.HTTP_429){
            return Math.min(f.retryAfterMs(), policy.maxRetryAfterMs());
        }        
        long base = Math.min(policy.backoffBaseMs() << attemptNo, policy.backoffMaxMs());
        long jitter = (long) (base * policy.jitterRatio() * ThreadLocalRandom.current().nextDouble());
        return base + jitter;
    }

    private static void log(String instanceId, String event, String detail) {
        // 结构化日志（SLF4J）：可按级别过滤、可携带 MDC 上下文（requestId）
        log.info("[{}] event={} detail={}", instanceId, event, detail);
    }
}
