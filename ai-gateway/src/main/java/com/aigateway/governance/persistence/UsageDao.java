package com.aigateway.governance.persistence;

import com.aigateway.governance.model.UsageRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 用量表读写：批量写入（一次事务 N 条）+ 汇总查询。
 *
 * 返回列契约（TokenUsageStore.restore 与 /v1/admin/usage 都依赖，字段名必须一致）：
 * - sumByTokenId() 返回每行含 total_tokens 与 total_cost 两列（由调用方按令牌 quotaType 选量纲）；
 * - summarize() 返回 token_in / token_out / cost / count 汇总（管理端点展示用）。
 */
@Repository
public class UsageDao {

    private final JdbcTemplate jdbc;

    public UsageDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 批量写入（一次事务写 N 条，账本批量落库的落点） */
    public void batchInsert(List<UsageRecord> records) {
        jdbc.batchUpdate("""
                INSERT INTO usage_record (request_id, token_id, alias, channel_id, model,
                                          token_in, token_out, cost, latency_ms, result, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, records, records.size(), (ps, r) -> {
            ps.setString(1, r.requestId());
            ps.setString(2, r.tokenId());
            ps.setString(3, r.alias());
            ps.setString(4, r.channelId());
            ps.setString(5, r.model());
            ps.setLong(6, r.tokenIn());
            ps.setLong(7, r.tokenOut());
            ps.setDouble(8, r.cost());
            ps.setLong(9, r.latencyMs());
            ps.setString(10, r.result());
            ps.setTimestamp(11, new Timestamp(r.createdAt()));
        });
    }

    /** 按令牌汇总用量与成本（TokenUsageStore.restore 用；每行含 total_tokens / total_cost） */
    public List<Map<String, Object>> sumByTokenId() {
        return jdbc.queryForList("""
                SELECT token_id,
                       SUM(token_in) + SUM(token_out) AS total_tokens,
                       SUM(cost) AS total_cost
                FROM usage_record GROUP BY token_id
                """);
    }

    /** 按令牌 / 模型 / 时间窗汇总（管理端点 /v1/admin/usage 展示用） */
    public Map<String, Object> summarize(String tokenId, String model, int days) {
        StringBuilder sql = new StringBuilder("""
                SELECT COALESCE(SUM(token_in), 0) AS token_in,
                       COALESCE(SUM(token_out), 0) AS token_out,
                       COALESCE(SUM(cost), 0) AS cost,
                       COUNT(*) AS count
                FROM usage_record WHERE created_at >= ?
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(new Timestamp(System.currentTimeMillis() - days * 86_400_000L));
        if (tokenId != null && !tokenId.isBlank()) {
            sql.append(" AND token_id = ?");
            args.add(tokenId);
        }
        if (model != null && !model.isBlank()) {
            sql.append(" AND model = ?");
            args.add(model);
        }
        return jdbc.queryForMap(sql.toString(), args.toArray());
    }
}
