package com.aigateway.infra.config;

import com.aigateway.governance.config.GovernanceProperties;
import com.aigateway.governance.persistence.TokenDao;
import com.aigateway.governance.token.TokenManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * V4 装配类：治理相关 Bean 的启动初始化。
 * - TokenBootstrap：启动种子令牌入库 + 「治理开启且无任何令牌」时自动创建演示令牌
 *   （对照《版本4-详细实施计划》5.2 兼容性约定）。
 *
 * ⚠️ TokenManager.create 是 H1 手敲点：未手敲前调用会抛 not_implemented，
 * 本类 catch 后打日志跳过（启动不炸），手敲完成后重启即自动创建。
 */
@Configuration
public class GovernanceBeans {

    private static final Logger log = LoggerFactory.getLogger(GovernanceBeans.class);

    /** 令牌启动初始化（必须在 SchemaInitializer 建表之后） */
    @Bean
    @DependsOn("schemaInitializer")
    public TokenBootstrap tokenBootstrap(GovernanceProperties govProps,
                                         GatewayProperties props,
                                         TokenManager tokenManager,
                                         TokenDao tokenDao) {
        return new TokenBootstrap(govProps, props, tokenManager, tokenDao);
    }

    /**
     * 令牌种子 + 演示令牌：
     * 1. 配置种子令牌（gateway.tokens[]）逐个创建；
     * 2. 配置种子为空且库中为空 → 自动创建演示令牌 agw_demo_<16 hex> 并打印在启动日志
     *   （演示 A/B/C 直接可用）；enabled=false 时跳过。
     */
    public static class TokenBootstrap implements InitializingBean {

        private final GovernanceProperties govProps;
        private final GatewayProperties props;
        private final TokenManager tokenManager;
        private final TokenDao tokenDao;

        TokenBootstrap(GovernanceProperties govProps, GatewayProperties props,
                       TokenManager tokenManager, TokenDao tokenDao) {
            this.govProps = govProps;
            this.props = props;
            this.tokenManager = tokenManager;
            this.tokenDao = tokenDao;
        }

        @Override
        public void afterPropertiesSet() {
            if (!govProps.isEnabled()) {
                log.info("治理已禁用（governance.enabled=false），跳过令牌初始化");
                return;
            }
            // 1. 配置种子令牌
            for (GatewayProperties.TokenSeedDef seed : props.getTokens()) {
                try {
                    tokenManager.create(seed.getName(), seed.getQuotaLimit(), seed.getQuotaType(),
                            seed.getModelScope(), seed.getIpWhitelist(), seed.getExpiresAt());
                } catch (Exception e) {
                    // H1 未手敲时 create 抛 not_implemented：跳过，日志提示
                    log.warn("种子令牌 [{}] 创建失败（H1 未手敲?）: {}", seed.getName(), e.getMessage());
                }
            }
            // 2. 无任何令牌 → 演示令牌（配置种子为空且库中为空）
            if (props.getTokens().isEmpty() && tokenDao.findAll().isEmpty()) {
                try {
                    TokenManager.CreateResult r = tokenManager.create(
                            "demo", 1.0, "COST", "", "", -1);
                    // 明文只此一次：打印在启动日志（学习版 5.2 约定的演示入口）
                    log.info("已自动创建演示令牌（额度 $1.0）: {}", r.plain());
                } catch (Exception e) {
                    // H1 未手敲时 create 抛 not_implemented：跳过，日志提示
                    log.warn("演示令牌创建失败（H1 未手敲?）: {}", e.getMessage());
                }
            }
        }
    }
}
