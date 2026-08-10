package com.aigateway.plugin.example;

import com.aigateway.api.dto.ChatRequest;
import com.aigateway.core.exception.GatewayException;
import com.aigateway.plugin.context.PluginContext;
import com.aigateway.plugin.spi.GatewayPlugin;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 示例插件：复杂度识别。
 *
 * 职责：从请求消息里匹配关键词，写 {@code task_complexity} 信号
 * （COMPLEX / SIMPLE），供 CONDITIONAL 策略与 Scorer 动态调权消费。
 *
 * 演示“零侵入生效”：核心路由代码不动，只加一个类 + 配置挂载。
 */
@Component
public class ComplexityRecognizerPlugin implements GatewayPlugin {

    private Pattern keywordPattern = Pattern.compile("代码|算法|重构|设计");

    public String name() {
        return "complexity-recognizer";
    }

    public int order() {
        return 30;
    }

    /** 配置校验：keywordRegex 必须能编译成合法正则，否则启动失败 */
    public void validate(Map<String, Object> config) {
        String regex = (String) config.get("keywordRegex");
        if (regex != null && !regex.isBlank()) {
            try {
                Pattern.compile(regex);
            } catch (Exception e) {
                throw new GatewayException(500, "invalid_config",
                        "keywordRegex 不是合法正则: " + regex);
            }
        }
    }

    public void configure(Map<String, Object> config) {
        String regex = (String) config.get("keywordRegex");
        if (regex != null && !regex.isBlank()) {
            keywordPattern = Pattern.compile(regex);
        }
    }

    public void beforeDecision(PluginContext ctx) {
        String content = ctx.request().messages() == null ? "" : ctx.request().messages().stream()
                .map(ChatRequest.Message::content)
                .filter(s -> s != null)
                .collect(Collectors.joining(" "));
        boolean complex = keywordPattern.matcher(content).find();
        ctx.signals().set("task_complexity", complex ? "COMPLEX" : "SIMPLE");
    }
}
