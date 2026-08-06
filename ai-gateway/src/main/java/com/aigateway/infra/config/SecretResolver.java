package com.aigateway.infra.config;

import com.aigateway.core.exception.GatewayException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 密钥引用解析：支持 "env:XXX" 或空字符串。
 *
 * 为什么这样设计（学习）：
 * - model.yml 会提交到代码仓库，绝不能出现密钥明文；
 * - 配置里只写 env:OPENAI_API_KEY，程序启动时从环境变量读取；
 * - 解析时机在启动阶段（ModelRegistry 构造时），解析失败直接启动失败，
 *   让配置问题尽早暴露，而不是等线上请求才报错。
 */
@Component
public class SecretResolver {

    /**
     * 解析一个密钥引用。
     *
     * @param credentialsRef 形如 "env:XXX"；空字符串/空值表示“无需密钥”
     * @return 密钥值；无密钥时返回 {@link Optional#empty()}
     * @throws GatewayException 引用格式不支持时（500 invalid_config）
     */
    public Optional<String> resolve(String credentialsRef) {
        if (credentialsRef == null || credentialsRef.isBlank()) {
            return Optional.empty();
        }
        if (credentialsRef.startsWith("env:")) {
            String var = credentialsRef.substring(4); // 去掉 "env:" 前缀，得到环境变量名
            return Optional.ofNullable(System.getenv(var));
        }
        throw new GatewayException(500, "invalid_config",
                "credentialsRef 只支持 env: 引用: " + credentialsRef);
    }
}
