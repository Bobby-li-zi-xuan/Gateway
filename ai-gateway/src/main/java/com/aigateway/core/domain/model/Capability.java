package com.aigateway.core.domain.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Capability {
    private int contextLength;
    private boolean multimodal;
    private boolean toolCallSupport;
    private String codingAbility;    // LOW / MEDIUM / HIGH / MAX
    private String reasoningLevel;   // LOW / MEDIUM / HIGH
    private String qualityLevel;     // QUALITY_HIGH / QUALITY_MEDIUM / FAST
}