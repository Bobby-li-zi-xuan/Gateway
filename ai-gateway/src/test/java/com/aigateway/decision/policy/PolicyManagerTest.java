package com.aigateway.decision.policy;

import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.decision.model.Policy;
import com.aigateway.infra.config.GatewayProperties;
import com.aigateway.state.registry.ModelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 策略管理器启动校验单测（脚手架）：权重和、策略引用、条件规则 select 子集。
 */
class PolicyManagerTest {

    private final ModelRegistry registry = mock(ModelRegistry.class);

    @BeforeEach
    void setUp() {
        when(registry.aliases()).thenReturn(List.of("qwen"));
        when(registry.strategyOf("qwen")).thenReturn("balanced");
        when(registry.findByAlias("qwen")).thenReturn(List.of(
                instance("mock-a:qwen-large")));
    }

    private ModelInstance instance(String instanceId) {
        return new ModelInstance(instanceId, "qwen", "mock-a", "qwen-large",
                1, null, 0.001, 0.002, 0.9, 1000L);
    }

    private GatewayProperties propsWithPolicy(GatewayProperties.PolicyDef policy) {
        GatewayProperties props = new GatewayProperties();
        props.getPolicies().add(policy);
        return props;
    }

    private GatewayProperties.PolicyDef multiObjective(String name, Map<String, Double> weights) {
        GatewayProperties.PolicyDef def = new GatewayProperties.PolicyDef();
        def.setName(name);
        def.setType("MULTI_OBJECTIVE");
        def.setWeights(weights);
        return def;
    }

    @Test
    void validConfig_shouldResolveAndExposeBuiltins() {
        GatewayProperties.PolicyDef policy = multiObjective("balanced",
                Map.of("latency", 0.25, "cost", 0.25, "quality", 0.25, "health", 0.25));

        PolicyManager manager = new PolicyManager(propsWithPolicy(policy), registry);

        assertThat(manager.resolve("qwen").name()).isEqualTo("balanced");
        assertThat(manager.resolve("qwen").weights().get("latency")).isEqualTo(0.25);
        assertThat(manager.byName("cost-first")).isNotNull(); // 内置策略可用
    }

    @Test
    void weightsSumNotOne_shouldFailStartup() {
        GatewayProperties.PolicyDef bad = multiObjective("bad",
                Map.of("latency", 0.5, "cost", 0.2, "quality", 0.2, "health", 0.2));

        assertThatThrownBy(() -> new PolicyManager(propsWithPolicy(bad), registry))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("权重和必须为 1");
    }

    @Test
    void missingStrategyReference_shouldFailStartup() {
        when(registry.strategyOf("qwen")).thenReturn("no-such-policy");

        assertThatThrownBy(() -> new PolicyManager(new GatewayProperties(), registry))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("不存在的策略");
    }

    @Test
    void conditionalSelectOutsideAlias_shouldFailStartup() {
        when(registry.strategyOf("qwen")).thenReturn("cond");

        GatewayProperties.PolicyDef cond = new GatewayProperties.PolicyDef();
        cond.setName("cond");
        cond.setType("CONDITIONAL");
        cond.setDefaultStrategy("balanced");
        GatewayProperties.RuleDef rule = new GatewayProperties.RuleDef();
        GatewayProperties.ConditionDef when = new GatewayProperties.ConditionDef();
        when.setSignal("task_complexity");
        when.setOp("EQ");
        when.setValue("COMPLEX");
        rule.setWhen(when);
        GatewayProperties.CandidateRef ref = new GatewayProperties.CandidateRef();
        ref.setChannelId("mock-b");      // 不属于 qwen 的候选
        ref.setModel("qwen-small");
        rule.getSelect().add(ref);
        cond.getRules().add(rule);

        assertThatThrownBy(() -> new PolicyManager(propsWithPolicy(cond), registry))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("不属于该别名的候选");
    }

    @Test
    void conditionalWithoutDefaultStrategy_shouldFailStartup() {
        GatewayProperties.PolicyDef cond = new GatewayProperties.PolicyDef();
        cond.setName("cond");
        cond.setType("CONDITIONAL");
        GatewayProperties.RuleDef rule = new GatewayProperties.RuleDef();
        GatewayProperties.ConditionDef when = new GatewayProperties.ConditionDef();
        when.setSignal("task_complexity");
        rule.setWhen(when);
        GatewayProperties.CandidateRef ref = new GatewayProperties.CandidateRef();
        ref.setChannelId("mock-a");
        ref.setModel("qwen-large");
        rule.getSelect().add(ref);
        cond.getRules().add(rule);

        assertThatThrownBy(() -> new PolicyManager(propsWithPolicy(cond), registry))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("defaultStrategy");
    }

    private GatewayProperties.PolicyDef conditional(String name, String defaultStrategy) {
        GatewayProperties.PolicyDef def = new GatewayProperties.PolicyDef();
        def.setName(name);
        def.setType("CONDITIONAL");
        def.setDefaultStrategy(defaultStrategy);
        GatewayProperties.RuleDef rule = new GatewayProperties.RuleDef();
        GatewayProperties.ConditionDef when = new GatewayProperties.ConditionDef();
        when.setSignal("task_complexity");
        when.setOp("EQ");
        when.setValue("COMPLEX");
        rule.setWhen(when);
        GatewayProperties.CandidateRef ref = new GatewayProperties.CandidateRef();
        ref.setChannelId("mock-a");
        ref.setModel("qwen-large");
        rule.getSelect().add(ref);
        def.getRules().add(rule);
        return def;
    }

    @Test
    void conditionalFallbackDefinedLater_shouldStartup() {
        // 两阶段校验（P3）：被引用的 MULTI_OBJECTIVE 回退策略定义在 CONDITIONAL 之后也能启动
        GatewayProperties props = new GatewayProperties();
        props.getPolicies().add(conditional("cond", "custom-multi"));
        props.getPolicies().add(multiObjective("custom-multi",
                Map.of("latency", 0.25, "cost", 0.25, "quality", 0.25, "health", 0.25)));

        PolicyManager manager = new PolicyManager(props, registry);

        assertThat(manager.byName("cond")).isNotNull();
        assertThat(manager.byName("custom-multi")).isNotNull();
    }

    @Test
    void conditionalFallbackNotMultiObjective_shouldFailStartup() {
        // 回退策略是另一个 CONDITIONAL → 仍须拒绝
        GatewayProperties props = new GatewayProperties();
        props.getPolicies().add(conditional("cond", "cond2"));
        props.getPolicies().add(conditional("cond2", "balanced"));

        assertThatThrownBy(() -> new PolicyManager(props, registry))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("defaultStrategy");
    }
}
