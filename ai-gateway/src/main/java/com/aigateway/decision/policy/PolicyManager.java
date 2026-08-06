package com.aigateway.decision.policy;

import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.decision.model.Condition;
import com.aigateway.decision.model.ConditionRule;
import com.aigateway.decision.model.Policy;
import com.aigateway.infra.config.GatewayProperties;
import com.aigateway.state.registry.ModelRegistry;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 策略管理器：加载/校验策略模板，并提供“别名 → 生效策略”查询。
 *
 * 启动校验（对照学习版文档 4.6）：
 * - 权重和为 1（浮点容差 1e-6）；
 * - 条件规则 select 必须是该别名候选的子集（只收窄、不扩大）；
 * - 别名引用的策略必须存在；CONDITIONAL 的 defaultStrategy 必须是 MULTI_OBJECTIVE。
 * 内置策略（balanced / latency-first / cost-first / quality-first）保证旧配置可启动。
 */
@Component
public class PolicyManager {

    private static final double WEIGHT_EPSILON = 1e-6;

    private final Map<String, Policy> policies = new ConcurrentHashMap<>();
    private final Map<String, String> aliasToStrategy = new ConcurrentHashMap<>();

    public PolicyManager(GatewayProperties props, ModelRegistry registry) {
        registerBuiltins();
        Set<String> userNames = new HashSet<>();
        for (GatewayProperties.PolicyDef def : props.getPolicies()) {
            // 用户定义可覆盖内置同名策略；但用户之间不允许重名
            if (def.getName() == null || def.getName().isBlank()) {
                throw new GatewayException(500, "invalid_config", "策略 name 不能为空");
            }
            if (!userNames.add(def.getName())) {
                throw new GatewayException(500, "invalid_config",
                        "重复的策略名: " + def.getName());
            }
            policies.put(def.getName(), toPolicy(def));
        }

        // 交叉校验：每个 alias 的 strategy 必须存在；CONDITIONAL 的 select 必须是该别名候选子集
        for (String alias : registry.aliases()) {
            String strategy = registry.strategyOf(alias);
            Policy policy = policies.get(strategy);
            if (policy == null) {
                throw new GatewayException(500, "invalid_config",
                        "alias[" + alias + "] 引用了不存在的策略: " + strategy);
            }
            if (policy.type() == Policy.PolicyType.CONDITIONAL) {
                validateSelectSubset(alias, policy, registry);
            }
            aliasToStrategy.put(alias, strategy);
        }
    }

    /** 内置策略：缺省配置也能启动（对应版本 1 的无策略配置） */
    private void registerBuiltins() {
        policies.put("balanced", new Policy("balanced", Policy.PolicyType.MULTI_OBJECTIVE,
                Map.of("latency", 0.25, "cost", 0.25, "quality", 0.25, "health", 0.25),
                List.of(), null));
        policies.put("latency-first", new Policy("latency-first", Policy.PolicyType.MULTI_OBJECTIVE,
                Map.of("latency", 0.6, "cost", 0.1, "quality", 0.15, "health", 0.15),
                List.of(), null));
        policies.put("cost-first", new Policy("cost-first", Policy.PolicyType.MULTI_OBJECTIVE,
                Map.of("latency", 0.1, "cost", 0.6, "quality", 0.2, "health", 0.1),
                List.of(), null));
        policies.put("quality-first", new Policy("quality-first", Policy.PolicyType.MULTI_OBJECTIVE,
                Map.of("latency", 0.15, "cost", 0.1, "quality", 0.6, "health", 0.15),
                List.of(), null));
    }

    private Policy toPolicy(GatewayProperties.PolicyDef def) {
        Policy.PolicyType type;
        try {
            type = Policy.PolicyType.valueOf(def.getType().toUpperCase());
        } catch (Exception e) {
            throw new GatewayException(500, "invalid_config",
                    "策略[" + def.getName() + "] 的 type 非法: " + def.getType());
        }
        switch (type) {
            case MULTI_OBJECTIVE -> validateWeights(def);
            case WEIGHTED_RANDOM -> { /* 用候选自身 weight，无需权重向量 */ }
            case CONDITIONAL -> validateConditional(def);
        }
        return new Policy(def.getName(), type, Map.copyOf(def.getWeights()),
                def.getRules().stream()
                        .map(r -> new ConditionRule(
                                new Condition(r.getWhen().getSignal(),
                                        Condition.Op.valueOf(r.getWhen().getOp().toUpperCase()),
                                        r.getWhen().getValue()),
                                r.getSelect().stream()
                                        .map(GatewayProperties.CandidateRef::instanceId)
                                        .toList()))
                        .toList(),
                def.getDefaultStrategy());
    }

    private void validateWeights(GatewayProperties.PolicyDef def) {
        Map<String, Double> w = def.getWeights();
        for (String factor : List.of("latency", "cost", "quality", "health")) {
            if (!w.containsKey(factor)) {
                throw new GatewayException(500, "invalid_config",
                        "策略[" + def.getName() + "] 缺少权重因子: " + factor);
            }
        }
        double sum = w.values().stream().mapToDouble(Double::doubleValue).sum();
        if (Math.abs(sum - 1.0) > WEIGHT_EPSILON) {
            throw new GatewayException(500, "invalid_config",
                    "策略[" + def.getName() + "] 权重和必须为 1，当前: " + sum);
        }
    }

    private void validateConditional(GatewayProperties.PolicyDef def) {
        if (def.getRules().isEmpty()) {
            throw new GatewayException(500, "invalid_config",
                    "CONDITIONAL 策略[" + def.getName() + "] 至少需要一条规则");
        }
        for (GatewayProperties.RuleDef rule : def.getRules()) {
            if (rule.getWhen() == null || rule.getWhen().getSignal() == null
                    || rule.getWhen().getSignal().isBlank()) {
                throw new GatewayException(500, "invalid_config",
                        "策略[" + def.getName() + "] 存在缺少 signal 的规则");
            }
            try {
                Condition.Op.valueOf(rule.getWhen().getOp().toUpperCase());
            } catch (Exception e) {
                throw new GatewayException(500, "invalid_config",
                        "策略[" + def.getName() + "] 存在非法 op: " + rule.getWhen().getOp());
            }
            if (rule.getSelect().isEmpty()) {
                throw new GatewayException(500, "invalid_config",
                        "策略[" + def.getName() + "] 存在 select 为空的规则");
            }
        }
        String fallback = def.getDefaultStrategy();
        Policy fallbackPolicy = fallback == null ? null : policies.get(fallback);
        if (fallbackPolicy == null || fallbackPolicy.type() != Policy.PolicyType.MULTI_OBJECTIVE) {
            throw new GatewayException(500, "invalid_config",
                    "CONDITIONAL 策略[" + def.getName()
                            + "] 的 defaultStrategy 必须存在且为 MULTI_OBJECTIVE");
        }
    }

    private void validateSelectSubset(String alias, Policy policy, ModelRegistry registry) {
        Set<String> instanceIds = registry.findByAlias(alias).stream()
                .map(ModelInstance::instanceId).collect(Collectors.toSet());
        for (ConditionRule rule : policy.rules()) {
            for (String id : rule.select()) {
                if (!instanceIds.contains(id)) {
                    throw new GatewayException(500, "invalid_config",
                            "alias[" + alias + "] 的规则引用了不属于该别名的候选: " + id);
                }
            }
        }
    }

    /** 运行时查询：模型别名 → 生效策略 */
    public Policy resolve(String alias) {
        Policy policy = policies.get(aliasToStrategy.getOrDefault(alias, "balanced"));
        if (policy == null) {
            throw new GatewayException(500, "invalid_config",
                    "alias[" + alias + "] 没有可用策略（启动校验应已拦截）");
        }
        return policy;
    }

    /** 按策略名查询（CONDITIONAL 未命中回退用） */
    public Policy byName(String name) {
        return policies.get(name);
    }
}
