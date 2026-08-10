package com.aigateway.decision.policy;

import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.decision.model.Condition;
import com.aigateway.decision.model.ConditionRule;
import com.aigateway.decision.model.Policy;
import com.aigateway.decision.model.Policy.PolicyType;
import com.aigateway.infra.config.GatewayProperties;
import com.aigateway.infra.config.GatewayProperties.CandidateRef;
import com.aigateway.infra.config.GatewayProperties.PolicyDef;
import com.aigateway.infra.config.GatewayProperties.RuleDef;
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
 * 启动校验：
 * - 权重和为 1（浮点容差 1e-6）；
 * - 条件规则 select 必须是该别名候选的子集（只收窄、不扩大）；
 * - 别名引用的策略必须存在；CONDITIONAL 的 defaultStrategy 必须是 MULTI_OBJECTIVE。
 * 内置策略（balanced / latency-first / cost-first / quality-first）保证旧配置可启动。
 */
@Component
public class PolicyManager{

    private static final double WEIGHT_EPSILON = 1e-6;

    private final Map<String, Policy> policies = new ConcurrentHashMap<>();
    private final Map<String, String> aliasToStrategy = new ConcurrentHashMap<>();

    public PolicyManager(GatewayProperties props, ModelRegistry registry) {
        registerBuiltins();
        Set<String> userNames = new HashSet<>();
        for (PolicyDef def : props.getPolicies()) {
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

        // 两阶段校验：全部注册后再统一校验 CONDITIONAL 的 defaultStrategy 引用，
        // 避免被引用的回退策略定义在后（列表顺序）时启动误报
        for (PolicyDef def : props.getPolicies()) {
            Policy policy = policies.get(def.getName());
            if (policy != null && policy.type() == PolicyType.CONDITIONAL) {
                validateConditionalFallback(def);
            }
        }

        // 交叉校验：每个 alias 的 strategy 必须存在；CONDITIONAL 的 select 必须是该别名候选子集
        for (String alias : registry.aliases()) {
            String strategy = registry.strategyOf(alias);
            Policy policy = policies.get(strategy);
            if (policy == null) {
                throw new GatewayException(500, "invalid_config",
                        "alias[" + alias + "] 引用了不存在的策略: " + strategy);
            }
            if (policy.type() == PolicyType.CONDITIONAL) {
                validateSelectSubset(alias, policy, registry);
            }
            aliasToStrategy.put(alias, strategy);
        }
    }

    /** 内置策略：缺省配置也能启动（对应版本 1 的无策略配置） */
    private void registerBuiltins() {
        policies.put("balanced", new Policy("balanced", PolicyType.MULTI_OBJECTIVE,
                Map.of("latency", 0.25, "cost", 0.25, "quality", 0.25, "health", 0.25),
                List.of(), null));
        policies.put("latency-first", new Policy("latency-first", PolicyType.MULTI_OBJECTIVE,
                Map.of("latency", 0.6, "cost", 0.1, "quality", 0.15, "health", 0.15),
                List.of(), null));
        policies.put("cost-first", new Policy("cost-first", PolicyType.MULTI_OBJECTIVE,
                Map.of("latency", 0.1, "cost", 0.6, "quality", 0.2, "health", 0.1),
                List.of(), null));
        policies.put("quality-first", new Policy("quality-first", PolicyType.MULTI_OBJECTIVE,
                Map.of("latency", 0.15, "cost", 0.1, "quality", 0.6, "health", 0.15),
                List.of(), null));
    }

    private Policy toPolicy(PolicyDef def) {
        PolicyType type;
        try {
            type = PolicyType.valueOf(def.getType().toUpperCase());
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
                                        .map(CandidateRef::instanceId)
                                        .toList()))
                        .toList(),
                def.getDefaultStrategy());
    }

    private void validateWeights(PolicyDef def) {
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

    private void validateConditional(PolicyDef def) {
        if (def.getRules().isEmpty()) {
            throw new GatewayException(500, "invalid_config",
                    "CONDITIONAL 策略[" + def.getName() + "] 至少需要一条规则");
        }
        for (RuleDef rule : def.getRules()) {
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
            // value 类型启动期校验：IN 必须是 List、EQ/NE 必须是标量（null 视为标量），
            // 否则运行期 Scheduler.matches 的 (List<?>) 强转会抛 ClassCastException（见 23 节风险 10）
            Object value = rule.getWhen().getValue();
            if (rule.getWhen().getOp().equalsIgnoreCase("IN")) {
                if (!(value instanceof List<?>)) {
                    throw new GatewayException(500, "invalid_config",
                            "策略[" + def.getName() + "] 规则 " + rule.getWhen().getSignal()
                                    + " 的 op=IN 时 value 必须是列表");
                }
            } else if (value instanceof List<?>) {
                throw new GatewayException(500, "invalid_config",
                        "策略[" + def.getName() + "] 规则 " + rule.getWhen().getSignal()
                                + " 的 op=" + rule.getWhen().getOp() + " 时 value 必须是标量");
            }
            if (rule.getSelect().isEmpty()) {
                throw new GatewayException(500, "invalid_config",
                        "策略[" + def.getName() + "] 存在 select 为空的规则");
            }
        }
    }

    /** 仅校验规则本身（与定义顺序无关）；defaultStrategy 引用在全部注册后统一校验 */
    private void validateConditionalFallback(PolicyDef def) {
        String fallback = def.getDefaultStrategy();
        Policy fallbackPolicy = fallback == null ? null : policies.get(fallback);
        if (fallbackPolicy == null || fallbackPolicy.type() != PolicyType.MULTI_OBJECTIVE) {
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
