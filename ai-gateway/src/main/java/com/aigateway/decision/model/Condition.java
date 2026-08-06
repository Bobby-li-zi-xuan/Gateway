package com.aigateway.decision.model;

/**
 * 条件表达式：signal 是信号名；op 支持 EQ / NE / IN；value 是标量或列表。
 */
public record Condition(String signal, Op op, Object value) {
    public enum Op { EQ, NE, IN }
}
