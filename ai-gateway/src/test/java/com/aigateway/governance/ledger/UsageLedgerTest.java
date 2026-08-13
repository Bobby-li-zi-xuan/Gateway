package com.aigateway.governance.ledger;

import com.aigateway.governance.budget.BudgetManager;
import com.aigateway.governance.config.GovernanceProperties;
import com.aigateway.governance.model.UsageRecord;
import com.aigateway.governance.persistence.UsageDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * H6 单测骨架（对照《版本4-详细实施计划》13.3）。
 * 手敲完成 UsageLedger 后删除 @Disabled 即可运行。
 */
@Disabled("TODO H6：手敲 UsageLedger 完成后启用")
class UsageLedgerTest {

    private UsageDao dao;
    private UsageLedger ledger;

    @BeforeEach
    void setUp() {
        dao = mock(UsageDao.class);
        // intervalMs=100（不重要，测试直接调 flush）、batchSize=50
        GovernanceProperties props = new GovernanceProperties();
        props.getUsageFlush().setIntervalMs(100);
        props.getUsageFlush().setBatchSize(50);
        ledger = new UsageLedger(dao, mock(BudgetManager.class), props, scheduler());
    }

    @Test
    void flush_writesBatchInOneCall() {
        UsageRecord r1 = record("r1"), r2 = record("r2");
        ledger.record(r1);
        ledger.record(r2);
        ledger.flush();

        verify(dao).batchInsert(List.of(r1, r2));         // 一次批量写两条
        assertThat(ledger.pending()).isZero();
    }

    @Test
    void flush_failure_returnsRecordsToQueue() {
        doThrow(new RuntimeException("db down")).when(dao).batchInsert(anyList());
        ledger.record(record("r1"));
        ledger.flush();
        assertThat(ledger.pending()).isEqualTo(1);        // 失败放回，等待重试
    }

    // —— 测试辅助 ——
    private static UsageRecord record(String rid) {
        return new UsageRecord(rid, "agw_00000001", "qwen", "mock-a", "qwen-large",
                10, 20, 0.0001, 100, "success", System.currentTimeMillis());
    }

    private static ScheduledExecutorService scheduler() {
        return Executors.newSingleThreadScheduledExecutor();
    }
}
