package com.aigateway.infra.config;

import com.aigateway.core.exception.GatewayException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 密钥引用解析：支持 "env:XXX" 或空字符串。
 * 配置里永远不出现密钥明文。
 */
@Component
public class SecretResolver {

    public Optional<String> resolve(String credentialsRef) {
        if (credentialsRef == null || credentialsRef.isBlank()) {
            return Optional.empty();
        }
        if (credentialsRef.startsWith("env:")) {
            String var = credentialsRef.substring(4);
            return Optional.ofNullable(System.getenv(var));
        }
        throw new GatewayException(500, "invalid_config",
                "credentialsRef 只支持 env: 引用: " + credentialsRef);
    }
}
