package com.aigateway.execution.model;

/**
 * 熔断状态机的三个状态（学习版 4.4）：关闭 → 打开 → 半开。
 * ModelState 快照里也用同一枚举，保证"运维状态"与"决策可见状态"同一套语义。
 */
public enum CircuitState { CLOSED, OPEN, HALF_OPEN }
