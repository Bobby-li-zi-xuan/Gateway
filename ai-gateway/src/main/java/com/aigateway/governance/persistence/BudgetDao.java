package com.aigateway.governance.persistence;

import com.aigateway.governance.model.Budget;
import com.aigateway.governance.model.BudgetPeriod;
import com.aigateway.governance.model.BudgetScope;
import com.aigateway.governance.model.LimitType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 预算表读写：按主键 upsert 预算定义、更新/读取 used_value 快照。
 * 内存计数是热路径，DB 快照是慢路径对账（由 UsageLedger.flush 顺带落库）。
 */
@Repository
public class BudgetDao {

    private final JdbcTemplate jdbc;

    public BudgetDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 创建或覆盖一个预算定义（同主键 upsert） */
    public void upsert(Budget b) {
        jdbc.update("""
                MERGE INTO budget (scope, scope_value, limit_type, period, limit_value, used_value)
                KEY (scope, scope_value, limit_type, period, limit_value)
                VALUES (?, ?, ?, ?, ?, ?)
                """, b.scope().name(), b.scopeValue(), b.limitType().name(),
                b.period().name(), b.limit(), b.used());
    }

    /** 全部预算定义（启动加载用） */
    public List<Budget> findAll() {
        return jdbc.query("SELECT * FROM budget", this::mapRow);
    }

    /** 更新 used 快照（对账落库：内存计数为准，覆盖 DB 值） */
    public void updateUsed(BudgetScope scope, String scopeValue, LimitType limitType,
                           BudgetPeriod period, double used) {
        jdbc.update("""
                UPDATE budget SET used_value = ?
                WHERE scope = ? AND scope_value = ? AND limit_type = ? AND period = ? AND limit_value = ?
                """, used, scope.name(), scopeValue, limitType.name(), period.name());
    }

    private Budget mapRow(ResultSet rs, int i) throws SQLException {
        return new Budget(
                BudgetScope.valueOf(rs.getString("scope")),
                rs.getString("scope_value"),
                LimitType.valueOf(rs.getString("limit_type")),
                BudgetPeriod.valueOf(rs.getString("period")),
                rs.getDouble("limit_value"));
    }
}
