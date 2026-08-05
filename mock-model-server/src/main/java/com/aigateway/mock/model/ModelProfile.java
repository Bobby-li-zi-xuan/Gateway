package com.aigateway.mock.model;

import java.util.Map;

/**
 * 描述一个 Mock 模型的全部行为参数。
 * 启动时通过 --mock.model=xxx 选择，剩余参数由此类提供默认值。
 */
public record ModelProfile (
    String name,                 //模型名称
    String version,              //版本
    long baseLatencyMs,          //基础推理延迟（模拟）
    double errorRate,            //错误率 0.0 - 1.0
    double streamingDelayMs,     //SSE每个Token之间的间隔
    int activeRequests,         ///health返回的当前活跃请求数
    int queueSize,               ///health返回的等待队列长度
    double gpuUsage,             ///health返回的GPU使用率
    double memoryUsage,          ///health返回的现存使用率
    String responseStyle,        //响应风格：CODE / ANALYSIS / CHAT
    Map<String, Object> metadata //额外元数据
){}
