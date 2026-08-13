package com.aigateway.governance.model;

/** 计量结果：一次成功请求的 token 与成本（TokenMeter 产出，BudgetManager 消费） */
public record MeteredUsage(
        long tokenIn,
        long tokenOut,
        double cost,          // 美元
        boolean estimated     // true = 使用了近似算法（usage 缺失）
) {
    public long totalTokens() { return tokenIn + tokenOut; }
}
