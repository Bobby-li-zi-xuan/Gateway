package com.aigateway.decision.model;

/**
 * 四个评分因子的载体（原始值或归一化值复用同一 record）。
 */
public record ScoreFactors(double latency, double cost, double quality, double health) {}
