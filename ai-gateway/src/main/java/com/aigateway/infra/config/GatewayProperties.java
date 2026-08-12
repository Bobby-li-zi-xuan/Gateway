package com.aigateway.infra.config;

import com.aigateway.core.domain.model.Capability;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关配置（前缀 gateway），对应 model.yml：
 * channels（渠道）+ models（别名 → 候选）+ health（健康检查参数）
 * + decision（V2 决策引擎参数）+ policies（V2 策略模板）+ plugins（V2 插件挂载）。
 *
 * 学习要点：
 * - {@code @ConfigurationProperties} 会把 model.yml 里以 gateway 开头的配置自动绑定到
 *   本类字段（宽松绑定：intervalSeconds ↔ interval-seconds 均可）；
 * - 字段带默认值（如 health 默认 5 秒），配置缺省时不报错；
 * - 用静态嵌套类表达“渠道定义 / 模型定义 / 候选定义 / 策略定义 / 插件定义”的层级结构，
 *   与 YAML 一一对应。
 */
@Configuration
@ConfigurationProperties(prefix = "gateway")
@Data
public class GatewayProperties {

    /** 健康检查参数：间隔、单次探活超时、连续失败多少次后判为不健康 */
    private Health health = new Health();

    /** V3 容错治理参数：重试/超时/熔断/冷却（全部有缺省，旧配置可直接启动） */
    private Execution execution = new Execution();

    /** V2 决策引擎参数：日志容量、EWMA 系数、默认输出 token 数 */
    private Decision decision = new Decision();

    /** 渠道列表（上游端点） */
    private List<ChannelDef> channels = new ArrayList<>();

    /** 模型别名 → 候选实例列表 */
    private List<ModelDef> models = new ArrayList<>();

    /** V2 策略模板（MULTI_OBJECTIVE / WEIGHTED_RANDOM / CONDITIONAL） */
    private List<PolicyDef> policies = new ArrayList<>();

    /** V2 插件挂载（按 name 匹配 SPI 实例） */
    private List<PluginDef> plugins = new ArrayList<>();

    /** 健康检查参数定义 */
    @Data
    public static class Health {
        private long intervalSeconds = 5;        // 探活周期（秒）
        private long timeoutMs = 2000;           // 单次探活超时（毫秒）
        private int consecutiveFailures = 3;     // 连续失败多少次后标记不健康
    }

    /** V3 容错治理参数定义（嵌套类字段名与 model.yml 的 execution 段一一对应） */
    @Data
    public static class Execution {
        private long totalMs = 60_000;                       // 整条降级链总时长上限
        private TimeoutDef defaultTimeout = new TimeoutDef(); // 渠道级缺省分层超时
        private RetryDef retry = new RetryDef();
        private CircuitBreakerDef circuitBreaker = new CircuitBreakerDef();
        private CooldownDef cooldown = new CooldownDef();
        private StateDef state = new StateDef();
    }

    /** 分层超时定义：连接 / 完整响应 / 首字节 / 空闲 */
    @Data
    public static class TimeoutDef {
        private long connectMs = 500;
        private long requestMs = 30_000;
        private long firstByteMs = 10_000;
        private long idleMs = 30_000;
    }

    /** 重试配置定义 */
    @Data
    public static class RetryDef {
        private int maxAttemptsPerCandidate = 1;       // 每个候选默认尝试次数（1 = 不重试同实例）
        private boolean sameInstanceOnConnectionFailure = true; // 连接级失败允许同实例重试一次
        private boolean respectRetryAfter = true;      // 429 优先遵循 Retry-After
        private long maxRetryAfterMs = 5_000;          // Retry-After 等待上限
        private long backoffBaseMs = 100;              // 指数退避基数
        private long backoffMaxMs = 2_000;             // 退避上限
        private double jitterRatio = 0.2;              // 随机抖动比例（防惊群）
        private List<Integer> retryableStatuses = List.of(429, 500, 502, 503, 504);
    }

    /** 熔断配置定义 */
    @Data
    public static class CircuitBreakerDef {
        private int windowSize = 100;                  // 环形窗口大小
        private int minimumRequests = 5;               // 最少样本数（样本不足不触发）
        private double failureRateThreshold = 0.5;     // 失败率阈值（0~1）
        private long slowCallThresholdMs = 10_000;     // 慢调用判定阈值
        private double slowCallRateThreshold = 0.5;    // 慢调用率阈值（0~1）
        private long openDurationMs = 30_000;          // 打开状态持续时间
        private int halfOpenMaxRequests = 1;           // 半开态放行的探测请求数
    }

    /** 冷却配置定义 */
    @Data
    public static class CooldownDef {
        private int consecutiveFailures = 5;           // 连续失败进入冷却的阈值
        private long cooldownMs = 30_000;              // 冷却时长
        private long maxCooldownMs = 300_000;          // 冷却翻倍上限
        private boolean recoveryProbe = true;          // 恢复探测开关
    }

    /** 状态管道配置定义 */
    @Data
    public static class StateDef {
        private long flushIntervalMs = 500;            // 状态事件管道聚合周期（毫秒）
    }

    /** V2 决策引擎参数定义 */
    @Data
    public static class Decision {
        private int logSize = 100;               // 决策日志环形缓冲容量
        private double ewmaAlpha = 0.3;          // 延迟 EWMA 系数
        private double errorRateAlpha = 0.1;     // 错误率 EWMA 系数
        private int defaultOutputTokens = 256;   // 成本预估用的默认输出 token
    }

    /** 渠道定义：一个上游端点 */
    @Data
    public static class ChannelDef {
        private String id;              // 渠道 ID（唯一）
        private String provider;        // 供应商类型
        private String baseUrl;         // 上游基础地址
        private String credentialsRef = ""; // 密钥引用（env:XXX），默认为空 = 无需鉴权
        private int weight = 1;         // 渠道权重
        private TimeoutDef timeout;     // V3 渠道级超时覆盖（可选；null = 用 execution.defaultTimeout）
    }

    /** 模型定义：一个对外别名下的候选列表 */
    @Data
    public static class ModelDef {
        private String alias;                        // 对外模型名（客户端传入）
        private String strategy = "balanced";        // V2：引用 policies 里的策略名
        private List<CandidateDef> candidates = new ArrayList<>(); // 候选实例
    }

    /** 候选定义：绑定到某个渠道的某个上游模型 */
    @Data
    public static class CandidateDef {
        private String channelId;       // 引用的渠道
        private String model;           // 上游真实模型名
        private int weight = 1;         // 候选权重（同别名下按比例路由）
        private Capability capability;  // 能力画像（V2 调度用）
        private double priceIn;         // V2：每 1K input tokens 价格（成本因子）
        private double priceOut;        // V2：每 1K output tokens 价格
        private Double qualityScore;    // V2：0~1 静态质量分；null = 由 qualityLevel 推导
        private Long latencyProfileMs;  // V2：EWMA 延迟初始值；null = 1000
    }

    /** V2 策略模板定义 */
    @Data
    public static class PolicyDef {
        private String name;                       // 策略名（models[].strategy 引用）
        private String type;                       // MULTI_OBJECTIVE / WEIGHTED_RANDOM / CONDITIONAL
        private Map<String, Double> weights = new HashMap<>(); // MULTI_OBJECTIVE 权重向量
        private List<RuleDef> rules = new ArrayList<>();       // CONDITIONAL 规则
        private String defaultStrategy;            // CONDITIONAL 未命中时的回退策略名
    }

    /** V2 条件规则定义：命中后把候选集覆盖为 select */
    @Data
    public static class RuleDef {
        private ConditionDef when;                 // 触发条件
        private List<CandidateRef> select = new ArrayList<>(); // 命中选择的候选
    }

    /** V2 条件表达式定义：signal + op + value */
    @Data
    public static class ConditionDef {
        private String signal;                     // 信号名，如 task_complexity
        private String op = "EQ";                  // EQ（等于）、NE（不等于）、IN（包含于）
        private Object value;                      // 标量或列表（IN）
    }

    /** V2 候选引用：channelId + model 定位一个候选实例 */
    @Data
    public static class CandidateRef {
        private String channelId;
        private String model;
        public String instanceId() { return channelId + ":" + model; }
    }

    /** V2 插件挂载定义 */
    @Data
    public static class PluginDef {
        private String name;                       // 插件名（GatewayPlugin.name() 对应）
        private String scope = "GLOBAL";           // GLOBAL / ROUTE / MODEL
        private String scopeValue = "";            // ROUTE=别名；MODEL=instanceId
        private int order = 0;                     // 同阶段内执行顺序（数字大的先执行）
        private Map<String, Object> config = new HashMap<>(); // 插件实例配置
    }
}
