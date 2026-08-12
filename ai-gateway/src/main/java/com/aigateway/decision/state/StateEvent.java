package com.aigateway.decision.state;

import com.aigateway.execution.model.CircuitState;
import com.aigateway.execution.model.FailureType;

/**
 * 状态事件模型（脚手架，代码段 S12a）：
 * 所有会影响 ModelState 的"事实"都建模成事件：执行结果、慢调用、熔断转换、冷却进出。
 * sealed 接口：编译器保证新增事件类型时必须在此声明，聚合方 switch 穷举不漏分支。
 */
public sealed interface StateEvent permits
        StateEvent.Success,
        StateEvent.Failure,
        StateEvent.SlowCall,
        StateEvent.CircuitTransition,
        StateEvent.CooldownChange {

    /** 成功样本：更新 EWMA 延迟、错误率衰减 */
    record Success(String instanceId, long latencyMs) implements StateEvent {}

    /** 失败样本：更新错误率 */
    record Failure(String instanceId, FailureType type) implements StateEvent {}

    /** 慢调用样本：累计慢调用计数（熔断的慢调用率输入） */
    record SlowCall(String instanceId, long latencyMs) implements StateEvent {}

    /** 熔断状态转换：CLOSED → OPEN → HALF_OPEN → CLOSED */
    record CircuitTransition(String instanceId, CircuitState from, CircuitState to) implements StateEvent {}

    /** 冷却进出：true = 进入冷却；false = 冷却结束/恢复 */
    record CooldownChange(String channelId, boolean cooling, long cooldownMs) implements StateEvent {}
}
