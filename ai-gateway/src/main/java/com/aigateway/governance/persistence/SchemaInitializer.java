package com.aigateway.governance.persistence;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 启动时执行 schema.sql（幂等）；预留 schema_version 表，V5 热更新启用版本化迁移。
 */
@Component
public class SchemaInitializer implements InitializingBean {

    private final JdbcTemplate jdbc;

    public SchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void afterPropertiesSet() {
        // 读取 classpath:schema.sql 逐条执行（文件内全部是幂等 DDL）
        Resource res = new ClassPathResource("schema.sql");
        try (var reader = new BufferedReader(
                new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
            String sql = reader.lines()
                    .filter(l -> !l.isBlank() && !l.trim().startsWith("--"))
                    .collect(Collectors.joining("\n"));
            // H2 支持单语句 execute：按分号切分逐条执行（简单实现，不引 Flyway）
            for (String stmt : sql.split(";")) {
                if (!stmt.isBlank()) jdbc.execute(stmt);
            }
        } catch (IOException e) {
            throw new IllegalStateException("加载 schema.sql 失败", e);
        }
    }
}
