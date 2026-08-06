package com.aigateway.mock.service;

import com.aigateway.mock.model.ModelProfile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Mock 响应生成器：按模型风格生成差异化内容，并模拟延迟与故障。
 *
 * 学习要点：
 * - 每种 responseStyle（CODE / ANALYSIS / CHAT）有若干条预置模板，随机返回一条，
 *   让不同 mock 实例的输出“看起来像”不同模型；
 * - 延迟/故障概率来自 ModelProfile（可通过 /mock/behavior 动态调整）。
 */
@Component
public class ResponseGenerator {

    private final Random random = new Random();

    /** 预置响应模板：key = responseStyle，value = 可随机选择的回复列表 */
    private static final Map<String, List<String>> STYLE_RESPONSES = Map.of(
        "CODE", List.of(
            "```java\npublic class Solution {\n    // 这里是代码实现\n    public static void main(String[] args) {\n        System.out.println(\"Hello\");\n    }\n}\n```\n以上是代码实现。",
            "```python\ndef solve():\n    # 这里是 Python 实现\n    return result\n\nif __name__ == '__main__':\n    print(solve())\n```\n以上是完整代码。"
        ),
        "ANALYSIS", List.of(
            "从架构层面分析，该方案的核心优势在于：\n\n1. **可扩展性**：模块间通过接口通信，新增功能只需实现对应接口。\n2. **可维护性**：单一职责原则确保每个模块边界清晰。\n3. **性能**：异步非阻塞模型保证了高并发下的低延迟。\n\n综合考虑上述因素...",
            "深入分析这个问题，需要从多个维度考虑：\n\n首先从技术角度看...\n其次从业务角度看...\n最后从运维部署角度看...\n\n综上，推荐的方案是..."
        ),
        "CHAT", List.of(
            "好的，我来回答这个问题。简单来说就是这样，有什么其他需要了解的吗？",
            "收到。根据我的理解，您的问题核心在于几个方面。如果还有其他问题请继续提问。"
        )
    );

    /**
     * 根据模型风格生成差异化响应内容，默认使用 CHAT 风格。
     *
     * @param profile 当前模型画像（含 responseStyle）
     * @return 从预定义模板中随机返回一条回复
     */
    public String generate(ModelProfile profile) {
        // 找不到对应风格时回退到 CHAT，保证任何配置都能出内容
        List<String> candidates = STYLE_RESPONSES.
        getOrDefault(profile.responseStyle(), STYLE_RESPONSES.get("CHAT"));
        return candidates.get(random.nextInt(candidates.size()));
    }

    /**
     * 模拟 tokens/s 的生成速率：在基础延迟上叠加随机波动，
     * 返回该 content 的模拟延迟（毫秒）。
     */
    public long simulateLatency(ModelProfile profile, String content) {
        // 波动范围 [0, baseLatency/2)，让每次请求延迟不同、更像真实服务
        long variableDelay = random.nextLong(profile.baseLatencyMs() / 2);
        return profile.baseLatencyMs() + variableDelay;
    }

    /**
     * 判断本次请求是否应该模拟失败。
     * errorRate=0.0 永不失败；1.0 永远失败（故障注入演示用）。
     */
    public boolean shouldFail(ModelProfile profile) {
        return random.nextDouble() < profile.errorRate();
    }
}
