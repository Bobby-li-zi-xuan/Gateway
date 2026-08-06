package com.aigateway.plugin.context;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 路由信号表（LiteLLM RoutingContext.signals 的对应物）：
 * 插件之间、插件与决策器之间传递结构化键值。
 *
 * 写入幂等的约定（学习版文档 4.2）：同一次请求内重复执行插件链，
 * 同一信号 key 的最终值必须一致。实现时避免“先 get 再追加 list”这类非幂等写法；
 * 需要集合时整体 set 一个新集合。
 */
public final class Signals {

    private final Map<String, Object> values = new ConcurrentHashMap<>();

    /** 写入信号（可覆盖） */
    public void set(String key, Object value) {
        values.put(key, value);
    }

    /** 仅在 key 不存在时写入（“先到先得”语义） */
    public void setIfAbsent(String key, Object value) {
        values.putIfAbsent(key, value);
    }

    public Optional<Object> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public Optional<String> getString(String key) {
        return get(key).map(String::valueOf);
    }

    /** 把信号值读成字符串集合（支持 Set / List / 标量） */
    @SuppressWarnings("unchecked")
    public Optional<Set<String>> getStringSet(String key) {
        return get(key).map(v -> {
            if (v instanceof Set<?> s) {
                return s.stream().map(String::valueOf).collect(Collectors.toSet());
            }
            if (v instanceof List<?> l) {
                return l.stream().map(String::valueOf).collect(Collectors.toSet());
            }
            return Set.of(String.valueOf(v));
        });
    }

    /** 不可变快照（日志 / RoutingDecision 用） */
    public Map<String, Object> snapshot() {
        return Map.copyOf(values);
    }
}
