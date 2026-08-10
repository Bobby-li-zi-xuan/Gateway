package com.aigateway.plugin.example;

import com.aigateway.plugin.context.PluginContext;
import com.aigateway.plugin.spi.GatewayPlugin;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 示例插件（仅演示/测试用，默认关闭）：清空候选池，触发 fail-closed。
 *
 * 用途：演示《版本2-详细实施计划》第 21.5 节场景 C——
 * 插件把候选清空后，请求应被拒绝（503 candidates_cleared），
 * 而不是静默回退全量候选。
 */
@Component
public class ClearCandidatesTestPlugin implements GatewayPlugin {

    private boolean enabled = false;

    public String name() {
        return "clear-candidates";
    }

    public int order() {
        return 10;
    }

    public void configure(Map<String, Object> config) {
        this.enabled = Boolean.parseBoolean(String.valueOf(config.getOrDefault("enabled", false)));
    }

    public void beforeDecision(PluginContext ctx) {
        if (enabled) {
            ctx.candidates().clear(); // 模拟策略冲突 → 引擎抛 503 candidates_cleared
        }
    }
}
