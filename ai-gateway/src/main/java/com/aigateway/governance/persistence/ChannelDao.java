package com.aigateway.governance.persistence;

import com.aigateway.governance.channel.ChannelRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * 渠道表读写：CRUD + 软删除（对照《版本4-详细实施计划》16 节）。
 * 渠道档案落库是 V4 渠道管理的地基；V5 移除「DB 覆盖配置」机制后本类继续作为管理面数据源。
 */
@Repository
public class ChannelDao {

    private final JdbcTemplate jdbc;

    public ChannelDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 插入渠道（配置种子入库 / 管理端点创建） */
    public void insert(ChannelRecord r) {
        jdbc.update("""
                INSERT INTO channel (id, provider, base_url, credentials_ref,
                                     weight, enabled, deleted, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, r.id(), r.provider(), r.baseUrl(), r.credentialsRef(),
                r.weight(), r.enabled(), r.deleted(), new Timestamp(r.createdAt()));
    }

    /** 全量渠道（含软删除的，启动加载后由内存过滤） */
    public List<ChannelRecord> findAll() {
        return jdbc.query("SELECT * FROM channel", this::mapRow);
    }

    public void updateStatus(String id, boolean enabled) {
        jdbc.update("UPDATE channel SET enabled = ? WHERE id = ?", enabled, id);
    }

    public void updateWeight(String id, int weight) {
        jdbc.update("UPDATE channel SET weight = ? WHERE id = ?", weight, id);
    }

    /** 软删除：仅标记，不物理删除（有历史用量时保护） */
    public void softDelete(String id) {
        jdbc.update("UPDATE channel SET deleted = TRUE, enabled = FALSE WHERE id = ?", id);
    }

    /** 物理删除（仅无历史用量时可用） */
    public void delete(String id) {
        jdbc.update("DELETE FROM channel WHERE id = ?", id);
    }

    /** 某渠道是否有历史用量（ChannelStore 软删除判据） */
    public boolean hasChannelUsage(String id) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM usage_record WHERE channel_id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    private ChannelRecord mapRow(ResultSet rs, int i) throws SQLException {
        return new ChannelRecord(
                rs.getString("id"), rs.getString("provider"), rs.getString("base_url"),
                rs.getString("credentials_ref"), rs.getInt("weight"),
                rs.getBoolean("enabled"), rs.getBoolean("deleted"),
                rs.getTimestamp("created_at").getTime());
    }
}
