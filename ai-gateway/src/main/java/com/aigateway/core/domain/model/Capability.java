package com.aigateway.core.domain.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 模型能力画像：描述一个上游模型“擅长什么”。
 *
 * 学习要点：
 * - V1 只把它当作展示/预留数据（model.yml 里配置、启动时随实例加载），
 *   真正按能力做调度（如代码题优先选 codingAbility=MAX）属于 V2 决策引擎的范畴；
 * - 能力值是约定字符串而非枚举，便于配置自由扩展，但代价是少了编译期检查。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Capability {

    private int contextLength;        // 上下文窗口长度（token 数）
    private boolean multimodal;       // 是否支持图片等多模态输入
    private boolean toolCallSupport;  // 是否支持工具调用（function calling）
    private String codingAbility;     // 编码能力：LOW / MEDIUM / HIGH / MAX
    private String reasoningLevel;    // 推理深度：LOW / MEDIUM / HIGH
    private String qualityLevel;      // 输出质量档位：QUALITY_HIGH / QUALITY_MEDIUM / FAST
}
