package com.aigateway.plugin.context;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.decision.model.RoutingDecision;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 插件共享上下文：一次请求内插件链共用的可变对象。
 *
 * 语义（对照 LiteLLM Router Plugins）：
 * - candidates 只允许收窄（移除），不允许新增；决策前被清空 → fail-closed；
 * - signals 供插件之间、插件与决策器之间传递信息；
 * - decision 生成后只读（setDecision 只允许一次）。
 */
public class PluginContext {

    private final ChatRequest request;             // 原始请求
    private final String requestId;                // 全链路 ID
    private final String alias;                    // 当前模型别名
    private final Map<String, String> metadata;    // 调用方信息（tenant / headers），只读
    private final Signals signals = new Signals(); // 路由信号表
    private final List<ModelInstance> candidates;  // 候选集合（可写，只允许收窄）
    private RoutingDecision decision;              // 决策结果（生成后只读）
    private Throwable error;                       // ON_ERROR 钩子用

    public PluginContext(ChatRequest request, String requestId, String alias,
                         Map<String, String> metadata, List<ModelInstance> candidates) {
        this.request = request;
        this.requestId = requestId;
        this.alias = alias;
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        this.candidates = new ArrayList<>(candidates); // 防御性拷贝：不污染注册表
    }

    public ChatRequest request() { return request; }

    public String requestId() { return requestId; }

    public String alias() { return alias; }

    public Map<String, String> metadata() { return metadata; }

    public Signals signals() { return signals; }

    /** 候选集合（可变）：插件只允许移除，不允许新增 */
    public List<ModelInstance> candidates() { return candidates; }

    /** 整体替换候选（Filter Chain 用）；只允许收窄——新集合必须是当前候选的子集 */
    public void setCandidates(List<ModelInstance> narrowed) {
        if (!candidates.containsAll(narrowed)) {
            throw new GatewayException(500, "internal_error",
                    "candidates 只允许收窄：新候选集合包含原集合之外的实例");
        }
        candidates.clear();
        candidates.addAll(narrowed);
    }

    public RoutingDecision decision() { return decision; }

    /** 决策只允许设置一次：决策不可变，后续阶段只读 */
    public void setDecision(RoutingDecision decision) {
        if (this.decision != null) {
            throw new IllegalStateException("decision 已生成，不可覆盖");
        }
        this.decision = decision;
    }

    public Throwable error() { return error; }

    public void setError(Throwable error) { this.error = error; }

    /** 当前候选的 instanceId 集合（作用域匹配用） */
    public Set<String> candidateIds() {
        return candidates.stream().map(ModelInstance::instanceId).collect(Collectors.toSet());
    }
}
