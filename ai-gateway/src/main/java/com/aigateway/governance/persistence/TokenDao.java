package com.aigateway.governance.persistence;

import com.aigateway.governance.model.ApiToken;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * 令牌表读写（对照《版本4-详细实施计划》7.2）：
 * - 凭证只存哈希（token_hash 唯一索引），数据库泄露也拿不到可用凭证；
 * - 统一语义：已吊销（revoked_at IS NOT NULL）一律不可见——
 *   warmup 恢复 / findById / TokenUsageStore.restore 都靠 findAll() 拿令牌，
 *   过滤后吊销令牌任何路径都查不到（重启前后一致返回 UNKNOWN/401）。
 */
@Repository
public class TokenDao {

    private final JdbcTemplate jdbc;

    public TokenDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 插入令牌（仅创建时调用） */
    public void insert(ApiToken t) {
        jdbc.update("""
                INSERT INTO api_token (id, token_hash, name, quota_limit, quota_type,
                                       model_scope, ip_whitelist, expires_at, enabled, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, t.id(), t.tokenHash(), t.name(), t.quotaLimit(), t.quotaType(),
                t.modelScope(), t.ipWhitelist(), t.expiresAt(), t.enabled(),
                new Timestamp(t.createdAt()));
    }

    /** 按哈希查令牌（启动预热 + 缓存未命中兜底）；无则返回 null */
    public ApiToken findByHash(String tokenHash) {
        return jdbc.query("SELECT * FROM api_token WHERE token_hash = ? AND revoked_at IS NULL",
                this::mapRow, tokenHash).stream().findFirst().orElse(null);
    }

    /** 吊销：标记 revoked_at；enabled 同时置 false（缓存失效靠内存删除） */
    public void revoke(String id) {
        jdbc.update("UPDATE api_token SET revoked_at = ?, enabled = FALSE WHERE id = ?",
                new Timestamp(System.currentTimeMillis()), id);
    }

    /** 全量列表（脱敏显示用：不返回哈希）；已吊销一律不可见（见类注释） */
    public List<ApiToken> findAll() {
        return jdbc.query("SELECT * FROM api_token WHERE revoked_at IS NULL", this::mapRow);
    }

    private ApiToken mapRow(ResultSet rs, int i) throws SQLException {
        return new ApiToken(
                rs.getString("id"), rs.getString("name"), rs.getString("token_hash"),
                rs.getDouble("quota_limit"), rs.getString("quota_type"),
                rs.getString("model_scope"), rs.getString("ip_whitelist"),
                rs.getLong("expires_at"), rs.getBoolean("enabled"),
                rs.getTimestamp("created_at").getTime());
    }
}
