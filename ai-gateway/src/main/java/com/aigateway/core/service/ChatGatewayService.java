package com.aigateway.core.service;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.api.dto.ChatCompletion;
import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.decision.engine.DecisionEngine;
import com.aigateway.decision.engine.DecisionEngine.DecisionOutcome;
import com.aigateway.decision.log.DecisionLogStore;
import com.aigateway.decision.model.RoutingDecision;
import com.aigateway.decision.state.ModelStateStore;
import com.aigateway.execution.connector.OpenAIConnector;
import com.aigateway.observability.GatewayMetrics;
import com.aigateway.plugin.engine.PluginEngine;
import com.aigateway.plugin.spi.PluginPhase;
import com.aigateway.state.health.HealthChecker;
import com.aigateway.state.registry.ModelRegistry;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 网关主服务（V2 改造版）：决策与执行完全分离（设计文档 3.2）。
 *
 * 分工：
 * - 决策（去哪家）：DecisionEngine 跑完 插件→过滤→打分→调度，产出 RoutingDecision（主选+降级链+明细）；
 * - 执行（怎么调）：本类只消费决策结果调上游、失败降级、记录状态、触发执行阶段钩子。
 *
 */
@Service
public class ChatGatewayService {

    // 决策流水线：插件→过滤→打分→调度
    private final DecisionEngine decisionEngine;      
    // 决策日志环形缓冲：每次决策明细，Debug 接口可查
    private final DecisionLogStore decisionLogStore;  
    // 上游调用 + SSE 解析
    private final OpenAIConnector connector;          
    // 实例实时状态：EWMA 延迟/错误率/并发数，供下次决策打分
    private final ModelStateStore stateStore;         
    // Prometheus 指标：成功/失败/chunk/决策
    private final GatewayMetrics metrics;             
    // 执行阶段钩子：BEFORE_EXECUTION / AFTER_EXECUTION / ON_ERROR
    private final PluginEngine pluginEngine;          

    public ChatGatewayService(DecisionEngine decisionEngine, DecisionLogStore decisionLogStore,
                              OpenAIConnector connector, ModelStateStore stateStore,
                              GatewayMetrics metrics, PluginEngine pluginEngine) {
        this.decisionEngine = decisionEngine;
        this.decisionLogStore = decisionLogStore;
        this.connector = connector;
        this.stateStore = stateStore;
        this.metrics = metrics;
        this.pluginEngine = pluginEngine;
    }

    /**
     * 非流式入口：三步流程——决策 → 落决策日志 → 执行主选。
     * 决策失败（无候选/条件规则锁死白名单为空）会直接在 decide() 里抛 503，不会走到这里。
     */
    public ChatCompletion complete(ChatRequest request, String requestId, Map<String, String> metadata){
        DecisionOutcome outcome = decisionEngine.decide(request, requestId, metadata);
        // 决策明细先落库：执行结果与决策一一对应
        decisionLogStore.record(outcome.decision());
        return execute(outcome, request, requestId);
    }

     /**
     * 执行主选：先跑 BEFORE_EXECUTION 钩子（插件可改写请求体），成功记成功状态，
     * 失败记失败状态并沿降级链再试一次。
     *
     * 状态记录与 EWMA 的关系（对照 4.4 实时状态）：
     * beginRequest（并发 +1）→ 调用 → recordSuccess/recordFailure（更新 EWMA 延迟/错误率）→ endRequest（并发 −1）
     * 下次决策打分读到的就是本次修正后的状态——每个请求都在自我修正。
     */
    private ChatCompletion execute(DecisionOutcome outcome,
                                   ChatRequest request, String requestId){
        RoutingDecision decision = outcome.decision();
        // 清掉上一次的错误残留（同一个context跨截断复用）
        outcome.context().setError(null);
        pluginEngine.runPhase(PluginPhase.BEFORE_EXECUTION, outcome.context());

        long start = System.nanoTime();
        ModelInstance primary = decision.primary();
        stateStore.beginRequest(primary.instanceId());      //并发计数雏形
        try{
            ChatCompletion result = connector.complete(primary, request);
            stateStore.recordSuccess(primary.instanceId(), elapsedMs(start));
            metrics.success(request.model(), primary.instanceId());
            pluginEngine.runPhase(PluginPhase.AFTER_EXECUTION, outcome.context());
            return result;
        }catch(Exception e){
            // 失败样本：错误率向1滑动
            stateStore.recordFailure(primary.instanceId());
            metrics.failure(request.model(), primary.instanceId());

            //主选失败 -> 降级
            return fallbackOnce(outcome, request, requestId, e);
        }finally{
            // 无论成败都释放并发计数
            stateStore.endRequest(primary.instanceId());
        }
    }

    /**
     * V2 最小降级：主选失败时沿 fallbackChain 只再试一次（V3 做成完整降级链执行）。
     * 语义：降级目标是"决策时排好的顺序"，不是执行时重新加权随机——
     * 决策与执行完全分离的体现：去哪家是决策层定的，执行层不重新选。
     */
    private ChatCompletion fallbackOnce(DecisionEngine.DecisionOutcome outcome,
                                        ChatRequest request, String requestId, Exception cause){
        RoutingDecision decision = outcome.decision();
        if(decision.fallbackChain().isEmpty()){
            // 无降级可试 -> ON_ERROR 通知插件 -> 502
            throw new GatewayException(502, "upstream_failed", "主候选失败且无降级候选：" + cause.getMessage(), cause);
        }
        ModelInstance backup = decision.fallbackChain().get(0);
        log(requestId, "决策降级",
                decision.primary().instanceId() + " -> " + backup.instanceId());
        long start = System.nanoTime();
        stateStore.beginRequest(backup.instanceId());
        try{
            ChatCompletion result = connector.complete(backup, request);
            stateStore.recordSuccess(backup.instanceId(), elapsedMs(start));
            metrics.success(request.model(), backup.instanceId());
            pluginEngine.runPhase(PluginPhase.AFTER_EXECUTION, outcome.context());
            return result;
        }catch(Exception e2){
            stateStore.recordFailure(backup.instanceId());
            metrics.failure(request.model(), backup.instanceId());
            notifyError(outcome, e2);
            throw new GatewayException(502, "upstream_failed",
                    "主选与降级候选均失败: " + e2.getMessage(), e2);
        }finally{
            stateStore.endRequest(backup.instanceId());
        }
    }

     /** ON_ERROR 钩子：最终失败上抛给客户端前通知插件（ctx.error 置为原始异常，插件可读） */
    private void notifyError(DecisionEngine.DecisionOutcome outcome, Exception cause) {
        outcome.context().setError(cause);
        pluginEngine.runPhase(PluginPhase.ON_ERROR, outcome.context());
    }

    /** 耗时换算：nanoTime 差值 → 毫秒（为什么用 nanoTime 而非 currentTimeMillis：不受系统改时间影响） */
    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static void log(String requestId, String event, String detail) {
        System.out.printf("[requestId=%s] event=%s detail=%s%n", requestId, event, detail);
    }

    /**
     * 流式入口：与 V1 相同的安全边界——首字节前失败才可切换；已经开始输出则直接中断。
     *
     * 为什么有这个边界（对照 V1 设计要点）：流式一旦发出第一个 chunk，客户端就收到了部分内容，
     * 此时重试会让客户端看到重复/错位的内容（不可安全重放），所以宁可 502 中断。
     * 区别：降级目标从 decision.fallbackChain() 取（V1 是剩余候选重新加权随机）。
     */
    public void stream(ChatRequest request, String requestId, Map<String, String> metadata,
                       Consumer<ChatChunk> consumer) {
        DecisionOutcome outcome = decisionEngine.decide(request, requestId, metadata);
        decisionLogStore.record(outcome.decision());
        RoutingDecision decision = outcome.decision();

        // started 用单元素数组：lambda 里要修改外部变量，Java 要求它“有效最终”，
        // 数组元素不是变量本身，所以可以修改（学习点：lambda 捕获）
        boolean[] started = {false};
        long start = System.nanoTime();
        ModelInstance primary = decision.primary();
        stateStore.beginRequest(primary.instanceId());
        try {
            connector.stream(primary, request, chunk -> {
                if (!started[0]) {
                    started[0] = true;
                    // 流式样本：用首字节延迟记录成功（V3 再细化完成态）
                    stateStore.recordSuccess(primary.instanceId(), elapsedMs(start));
                }
                metrics.streamChunk(request.model(), primary.instanceId());
                consumer.accept(chunk);   // chunk 转给上层 Controller 写入 SseEmitter
            });
        } catch (Exception e) {
            if (started[0]) {
                // 已输出内容不可重放：直接 502（安全边界）
                notifyError(outcome, e);
                throw new GatewayException(502, "upstream_failed",
                        "流式输出中断: " + e.getMessage(), e);
            }
            // 首字节前失败：记录失败样本后尝试降级
            stateStore.recordFailure(primary.instanceId());
            metrics.failure(request.model(), primary.instanceId());
            if (!decision.fallbackChain().isEmpty()) {
                ModelInstance backup = decision.fallbackChain().get(0);
                log(requestId, "流式降级(首字节前)",
                        primary.instanceId() + " -> " + backup.instanceId());
                stateStore.beginRequest(backup.instanceId());
                try {
                    // 降级成功：直接复用 consumer 转发；与非流式同口径记成功指标（审查 #1）
                    connector.stream(backup, request, consumer);
                    metrics.success(request.model(), backup.instanceId());
                    return;
                } catch (Exception e2) {
                    // 与非流式一致的语义：主选与降级均失败 → 502 + 计数 + ON_ERROR
                    stateStore.recordFailure(backup.instanceId());
                    metrics.failure(request.model(), backup.instanceId());
                    notifyError(outcome, e2);
                    throw new GatewayException(502, "upstream_failed",
                            "主选与降级候选均失败: " + e2.getMessage(), e2);
                } finally {
                    stateStore.endRequest(backup.instanceId());
                }
            }
            notifyError(outcome, e);   // 无降级可试：ON_ERROR → 502
            throw new GatewayException(502, "upstream_failed",
                    "主选候选失败且无降级候选: " + e.getMessage(), e);
        } finally {
            stateStore.endRequest(primary.instanceId());   // 主选的并发计数无论成败都释放
        }
    }
}
