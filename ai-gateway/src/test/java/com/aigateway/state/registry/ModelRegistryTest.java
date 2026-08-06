package com.aigateway.state.registry;

import com.aigateway.core.domain.model.Capability;
import com.aigateway.core.domain.model.ModelInstance;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.infra.config.GatewayProperties;
import com.aigateway.infra.config.SecretResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型注册中心启动校验单测（计划第 16.1 节 + V2 新字段校验）。
 *
 * 覆盖：正常加载 / 引用不存在渠道 / 空候选 / 重复 alias /
 *       findAll 跨别名按 instanceId 去重 / V2 strategyOf 与价格质量校验。
 */
class ModelRegistryTest {

    /** SecretResolver 是无状态简单类，直接使用真实实例（避免 Mockito mock 具体类） */
    private final SecretResolver secretResolver = new SecretResolver();

    private GatewayProperties validProps() {
        GatewayProperties props = new GatewayProperties();

        GatewayProperties.ChannelDef a = new GatewayProperties.ChannelDef();
        a.setId("mock-a");
        a.setProvider("openai-compatible");
        a.setBaseUrl("http://localhost:8001");
        a.setWeight(10);
        GatewayProperties.ChannelDef b = new GatewayProperties.ChannelDef();
        b.setId("mock-b");
        b.setProvider("openai-compatible");
        b.setBaseUrl("http://localhost:8002");
        b.setWeight(5);
        props.getChannels().addAll(List.of(a, b));

        props.getModels().add(modelDef("qwen", candidate("mock-a", "qwen-large", 8),
                candidate("mock-b", "qwen-small", 2)));
        props.getModels().add(modelDef("deepseek-code", candidate("mock-b", "deepseek-code", 1)));
        return props;
    }

    private GatewayProperties.ModelDef modelDef(String alias, GatewayProperties.CandidateDef... candidates) {
        GatewayProperties.ModelDef def = new GatewayProperties.ModelDef();
        def.setAlias(alias);
        def.getCandidates().addAll(List.of(candidates));
        return def;
    }

    private GatewayProperties.CandidateDef candidate(String channelId, String model, int weight) {
        GatewayProperties.CandidateDef def = new GatewayProperties.CandidateDef();
        def.setChannelId(channelId);
        def.setModel(model);
        def.setWeight(weight);
        return def;
    }

    private ModelRegistry newRegistry(GatewayProperties props) {
        return new ModelRegistry(props, secretResolver);
    }

    @Test
    void validConfig_shouldLoadCandidates() {
        ModelRegistry registry = newRegistry(validProps());

        List<ModelInstance> qwen = registry.findByAlias("qwen");
        assertThat(qwen).hasSize(2);
        assertThat(qwen).extracting(ModelInstance::instanceId)
                .containsExactly("mock-a:qwen-large", "mock-b:qwen-small");
        assertThat(registry.findByAlias("unknown")).isEmpty();
    }

    @Test
    void missingChannel_shouldFailStartup() {
        // 候选引用了不存在的渠道 → 启动抛 invalid_config，错误信息可读
        GatewayProperties props = validProps();
        props.getModels().add(modelDef("bad", candidate("no-such-channel", "x", 1)));

        assertThatThrownBy(() -> newRegistry(props))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("no-such-channel");
    }

    @Test
    void emptyCandidates_shouldFailStartup() {
        GatewayProperties props = validProps();
        props.getModels().add(modelDef("empty-alias"));

        assertThatThrownBy(() -> newRegistry(props))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("empty-alias");
    }

    @Test
    void duplicateAlias_shouldFailStartup() {
        // 重复 alias 会让后配置的静默覆盖先配置的，必须拒绝
        GatewayProperties props = validProps();
        props.getModels().add(modelDef("qwen", candidate("mock-a", "qwen-large", 8)));

        assertThatThrownBy(() -> newRegistry(props))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("重复的 alias: qwen");
    }

    @Test
    void findAll_shouldDeduplicateAcrossAliases() {
        // 同一渠道+模型被两个别名引用 → findAll 只出现一次
        GatewayProperties props = validProps();
        props.getModels().add(modelDef("alias2", candidate("mock-a", "qwen-large", 1)));

        ModelRegistry registry = newRegistry(props);
        assertThat(registry.findAll())
                .extracting(ModelInstance::instanceId)
                .containsExactly("mock-a:qwen-large", "mock-b:qwen-small", "mock-b:deepseek-code");
    }

    @Test
    void strategyOf_shouldDefaultToBalancedAndRespectConfig() {
        GatewayProperties props = validProps();
        props.getModels().get(0).setStrategy("latency-first");

        ModelRegistry registry = newRegistry(props);
        assertThat(registry.strategyOf("qwen")).isEqualTo("latency-first");
        assertThat(registry.strategyOf("deepseek-code")).isEqualTo("balanced");
        assertThat(registry.strategyOf("unknown")).isEqualTo("balanced");
    }

    @Test
    void negativePrice_shouldFailStartup() {
        GatewayProperties.CandidateDef bad = candidate("mock-a", "qwen-large", 1);
        bad.setPriceIn(-0.01);

        GatewayProperties props = validProps();
        props.getModels().add(modelDef("bad-price", bad));

        assertThatThrownBy(() -> newRegistry(props))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("负价格");
    }

    @Test
    void qualityScoreOutOfRange_shouldFailStartup() {
        GatewayProperties.CandidateDef bad = candidate("mock-a", "qwen-large", 1);
        bad.setQualityScore(1.5);

        GatewayProperties props = validProps();
        props.getModels().add(modelDef("bad-quality", bad));

        assertThatThrownBy(() -> newRegistry(props))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("qualityScore");
    }

    @Test
    void qualityScore_shouldDeriveFromQualityLevelAndLatencyDefault() {
        GatewayProperties.CandidateDef def = candidate("mock-a", "qwen-large", 1);
        Capability capability = new Capability();
        capability.setContextLength(128000);
        capability.setQualityLevel("QUALITY_HIGH");
        def.setCapability(capability);

        GatewayProperties props = validProps();
        props.getModels().add(modelDef("derived", def));

        ModelInstance instance = newRegistry(props).findByAlias("derived").get(0);
        assertThat(instance.qualityScore()).isEqualTo(0.9);
        assertThat(instance.latencyProfileMs()).isEqualTo(1000L);
    }
}
