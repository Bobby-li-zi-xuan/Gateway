package com.aigateway.core.service;

import com.aigateway.api.dto.ChatChunk;
import com.aigateway.api.dto.ChatCompletion;
import com.aigateway.api.dto.ChatRequest;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 网关主流程：路由 + 转发 + 失败切换（阻塞式，运行在虚拟线程上）。
 *
 * 🖊 手敲 H2：本类全部手敲，按《版本1-详细实施计划》第 9 节实现。
 * 需要注入：ModelRegistry、HealthChecker、OpenAIConnector、GatewayMetrics。
 * 逻辑：findByAlias → 健康过滤（isHealthy）→ WeightedRandomPicker（H1）
 *       → connector 调用 → 失败换下一个候选（最多 maxAttempts 次，
 *         每次用“剩余候选”重新做加权随机）。
 */
@Service
public class ChatGatewayService {

    public ChatCompletion complete(ChatRequest request, String requestId) {
        throw new UnsupportedOperationException(
                "H2 未实现：请手敲 complete()（见详细实施计划第 9 节）");
    }

    public void stream(ChatRequest request, String requestId, Consumer<ChatChunk> consumer) {
        throw new UnsupportedOperationException(
                "H2 未实现：请手敲 stream()（见详细实施计划第 9 节）");
    }
}
