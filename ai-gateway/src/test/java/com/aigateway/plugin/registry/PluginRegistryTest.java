package com.aigateway.plugin.registry;

import com.aigateway.core.exception.GatewayException;
import com.aigateway.infra.config.GatewayProperties;
import com.aigateway.plugin.context.PluginContext;
import com.aigateway.plugin.spi.GatewayPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 插件注册表启动校验单测（脚手架）：
 * 未知插件名 / 重复挂载 / validate 失败 / 正常注册与配置应用。
 */
class PluginRegistryTest {

    /** 测试插件：可配置 validate 失败与 configure 记录 */
    private static final class TestPlugin implements GatewayPlugin {
        private boolean configured;
        private String configValue;

        public String name() { return "test-plugin"; }

        public void validate(Map<String, Object> config) {
            if (Boolean.parseBoolean(String.valueOf(config.getOrDefault("fail", false)))) {
                throw new GatewayException(500, "invalid_config", "test-plugin 配置非法");
            }
        }

        public void configure(Map<String, Object> config) {
            this.configured = true;
            this.configValue = String.valueOf(config.getOrDefault("k", ""));
        }

        boolean isConfigured() { return configured; }
        String configValue() { return configValue; }
    }

    private GatewayProperties propsWithPlugins(GatewayProperties.PluginDef... defs) {
        GatewayProperties props = new GatewayProperties();
        props.getPlugins().addAll(List.of(defs));
        return props;
    }

    private GatewayProperties.PluginDef pluginDef(String name, Map<String, Object> config) {
        GatewayProperties.PluginDef def = new GatewayProperties.PluginDef();
        def.setName(name);
        def.setConfig(config);
        return def;
    }

    @Test
    void unknownPluginName_shouldFailStartup() {
        assertThatThrownBy(() -> new PluginRegistry(
                propsWithPlugins(pluginDef("no-such-plugin", Map.of())), List.of(new TestPlugin())))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("不存在的插件");
    }

    @Test
    void duplicateMount_shouldFailStartup() {
        assertThatThrownBy(() -> new PluginRegistry(
                propsWithPlugins(pluginDef("test-plugin", Map.of()),
                        pluginDef("test-plugin", Map.of())),
                List.of(new TestPlugin())))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("重复挂载");
    }

    @Test
    void validateFailure_shouldFailStartup() {
        assertThatThrownBy(() -> new PluginRegistry(
                propsWithPlugins(pluginDef("test-plugin", Map.of("fail", true))),
                List.of(new TestPlugin())))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("配置非法");
    }

    @Test
    void validConfig_shouldRegisterAndConfigure() {
        TestPlugin plugin = new TestPlugin();

        PluginRegistry registry = new PluginRegistry(
                propsWithPlugins(pluginDef("test-plugin", Map.of("k", "v"))),
                List.of(plugin));

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.all().get(0).name()).isEqualTo("test-plugin");
        assertThat(plugin.isConfigured()).isTrue();
        assertThat(plugin.configValue()).isEqualTo("v");
    }
}
