package com.aigateway.governance.config;

import com.aigateway.infra.config.GatewayProperties;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 治理配置（前缀 gateway.governance），对应 model.yml 的 governance 段。
 * 独立于 GatewayProperties 绑定，字段命名即 V5 热更新后的运行时快照形状（不要改）。
 *
 * 段落说明（对照《版本4-详细实施计划》5.1 / 5.2）：
 * - enabled：总开关，false = 跳过鉴权与限流（V1~V3 行为原样）；
 * - db：H2 JDBC 连接（文件模式；测试用内存库）；
 * - estimation：近似计量系数（CJK 约 1 字/token，其他约 4 字符/token）；
 * - rateLimit：限流（管速度）参数——QPS / RPM 滑动窗口 + Token 速率占位修正；
 * - budget：配额（管总量）参数——预警阈值（软预算）+ 超额动作 REJECT/DEGRADE；
 * - usageFlush：用量批量落库参数（定时 flush 周期与单批上限）。
 */
@Configuration
@ConfigurationProperties(prefix = "gateway.governance")
@Data
public class GovernanceProperties {

    /** 治理总开关：false = 跳过鉴权与限流（演示旁路用），V1~V3 行为原样 */
    private boolean enabled = true;

    /** H2 连接参数（文件模式零外部依赖；测试覆盖为内存库） */
    private Db db = new Db();

    /** 近似计量系数 */
    private Estimation estimation = new Estimation();

    /** 限流（管速度） */
    private RateLimit rateLimit = new RateLimit();

    /** 配额（管总量） */
    private Budget budget = new Budget();

    /** 用量批量落库 */
    private UsageFlush usageFlush = new UsageFlush();

    /** 读取 gateway.decision.defaultOutputTokens（避免两处配置；BudgetFilter/GovernanceService 共用） */
    @Autowired
    private GatewayProperties gatewayProperties;

    /** 决策默认输出 token（成本预估口径与 Scorer 一致）；手动构造测试对象时为 null 兜底 256 */
    public int getDefaultOutputTokens() {
        return gatewayProperties == null ? 256
                : gatewayProperties.getDecision().getDefaultOutputTokens();
    }

    @Data
    public static class Db {
        private String url = "jdbc:h2:file:./data/gateway";   // 文件模式；测试用 jdbc:h2:mem:gateway
        private String user = "sa";
        private String password = "";
    }

    @Data
    public static class Estimation {
        private double charsPerToken = 4.0;    // 非 CJK 字符 → token 换算系数
        private double cjkTokenPerChar = 1.0;  // CJK 每字 token 数
    }

    @Data
    public static class RateLimit {
        private long defaultQps = 100;         // 令牌级缺省 QPS（1s 滑动窗口）
        private long defaultRpm = 6000;        // 令牌级缺省 RPM（60s 滑动窗口）
        private TokenRate tokenRate = new TokenRate();
    }

    @Data
    public static class TokenRate {
        private boolean enabled = false;       // Token 速率限流开关（可选）
        private long perSecondTokens = 20_000; // Token 速率上限（每秒 token）
    }

    @Data
    public static class Budget {
        private List<Double> warnRatios = List.of(0.7, 0.9); // 预警阈值（软预算，不阻断）
        private String exceedAction = "REJECT";              // REJECT（拒绝）/ DEGRADE（降级）
    }

    @Data
    public static class UsageFlush {
        private long intervalMs = 5_000;       // 定时 flush 周期
        private int batchSize = 100;           // 单批最大条数（达到即刷）
    }
}
