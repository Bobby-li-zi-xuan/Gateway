package com.aigateway.mock.service;

import com.aigateway.mock.model.ModelProfile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Random;

@Component
public class ResponseGenerator {
    private final Random random = new Random();

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

    /*
    根据模型风格生成差异化响应内容，默认为CHAT
    @return 从预定义的响应模板中随机返回一条回复
     */
    public String generate(ModelProfile profile){
        List<String> candidates = STYLE_RESPONSES.
        getOrDefault(profile.responseStyle(), STYLE_RESPONSES.get("CHAT"));
        return candidates.get(random.nextInt(candidates.size()));
    }

    /*
    模拟 tokens/s 的生成速率，计算返回该content的模拟延迟
    */
    public long simulateLatency(ModelProfile profile, String content){
        long variableDelay = random.nextLong(profile.baseLatencyMs() / 2);
        return profile.baseLatencyMs() + variableDelay;
    }

    /*
    判断本次请求是否应该模拟失败
    */
    public boolean shouldFail(ModelProfile profile){
        return random.nextDouble() < profile.errorRate();
    }
}
