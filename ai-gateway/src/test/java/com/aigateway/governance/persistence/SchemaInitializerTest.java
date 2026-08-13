package com.aigateway.governance.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 脚手架测试：SchemaInitializer 幂等建表 + 四张表存在（对照《版本4-详细实施计划》20.1）。
 * 用内存 H2（文档风险第 8 条：测试必须内存库，避免锁文件库）。
 */
class SchemaInitializerTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // 每个用例独立内存库（同名库内容会跨用例残留）；
        // DB_CLOSE_DELAY=-1：H2 内存库不随最后一个连接关闭而销毁
        //（裸 DriverManagerDataSource 每条语句单独开连接，不加会库毁于每语句后）
        String url = "jdbc:h2:mem:schema_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        DriverManagerDataSource ds = new DriverManagerDataSource(url, "sa", "");
        jdbc = new JdbcTemplate(ds);
    }

    @Test
    void twiceInit_isIdempotent_andCreatesFourTables() {
        SchemaInitializer initializer = new SchemaInitializer(jdbc);

        // 两次执行不报错（幂等）
        assertThatCode(initializer::afterPropertiesSet).doesNotThrowAnyException();
        assertThatCode(initializer::afterPropertiesSet).doesNotThrowAnyException();

        // 四张表都存在（H2 标识符默认转大写，按 UPPER 比较）
        List<String> tables = jdbc.queryForList(
                "SELECT UPPER(table_name) FROM information_schema.tables WHERE table_schema = 'PUBLIC'",
                String.class);
        assertThat(tables)
                .contains("CHANNEL", "API_TOKEN", "BUDGET", "USAGE_RECORD");
    }

    @Test
    void channelDaoCanInsertAndQuery_afterInit() {
        new SchemaInitializer(jdbc).afterPropertiesSet();
        // 打通 DAO 层：渠道插入 + 查询（证明 schema 与 DAO 的列契约一致）
        ChannelDao dao = new ChannelDao(jdbc);
        dao.insert(new com.aigateway.governance.channel.ChannelRecord(
                "mock-c", "openai-compatible", "http://localhost:9999",
                "", 1, true, false, System.currentTimeMillis()));
        assertThat(dao.findAll()).extracting(r -> r.id()).contains("mock-c");
    }
}
