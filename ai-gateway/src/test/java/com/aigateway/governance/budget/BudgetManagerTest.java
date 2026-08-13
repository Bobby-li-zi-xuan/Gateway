package com.aigateway.governance.budget;

import com.aigateway.governance.model.Budget;
import com.aigateway.governance.model.BudgetPeriod;
import com.aigateway.governance.model.BudgetScope;
import com.aigateway.governance.model.LimitType;
import com.aigateway.governance.persistence.BudgetDao;
import com.aigateway.observability.GatewayMetrics;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * H3 单测骨架（对照《版本4-详细实施计划》10.3）。
 * 手敲完成 BudgetManager 后删除 @Disabled 即可运行。
 */
@Disabled("TODO H3：手敲 BudgetManager 完成后启用")
class BudgetManagerTest {

    @Test
    void concurrentConsume_neverExceedsLimit() throws InterruptedException {
        BudgetManager mgr = new BudgetManager(mock(BudgetDao.class), List.of(0.7, 0.9), mock(GatewayMetrics.class));
        mgr.upsert(BudgetScope.GLOBAL, "", LimitType.TOKEN, BudgetPeriod.DAILY, 100);

        // 20 个线程各扣 10：总扣 200，上限 100 → 恰好 10 个成功
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return mgr.consume(BudgetScope.GLOBAL, "", 10, 0).ok();
            }));
        }
        start.countDown();
        long ok = futures.stream().filter(f -> {
            try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); }
        }).count();
        pool.shutdown();

        assertThat(ok).isEqualTo(10);                       // 并发下不超扣
        assertThat(mgr.remainingRatio(BudgetScope.GLOBAL, "")).isEqualTo(0.0);
    }

    @Test
    void periodRollover_resetsUsage() {
        BudgetManager mgr = new BudgetManager(mock(BudgetDao.class), List.of(), mock(GatewayMetrics.class));
        mgr.upsert(BudgetScope.GLOBAL, "", LimitType.TOKEN, BudgetPeriod.HOURLY, 100);
        mgr.consume(BudgetScope.GLOBAL, "", 60, 0);
        assertThat(mgr.remainingRatio(BudgetScope.GLOBAL, "")).isEqualTo(0.4);

        // 直接改 Budget 的周期号模拟跨小时（避免真实等待）
        Budget b = mgr.allBudgets().get(0);
        b.resetPeriod("2026081000");                        // 上一个周期号
        assertThat(mgr.remainingRatio(BudgetScope.GLOBAL, "")).isEqualTo(1.0); // 惰性重置
    }

    @Test
    void precheck_doesNotDeduct() {
        BudgetManager mgr = new BudgetManager(mock(BudgetDao.class), List.of(), mock(GatewayMetrics.class));
        mgr.upsert(BudgetScope.GLOBAL, "", LimitType.TOKEN, BudgetPeriod.DAILY, 100);
        assertThat(mgr.precheck(BudgetScope.GLOBAL, "", 100, 0)).isTrue();
        assertThat(mgr.precheck(BudgetScope.GLOBAL, "", 101, 0)).isFalse(); // 101 > 100 超限（预检不改状态）
        assertThat(mgr.remainingRatio(BudgetScope.GLOBAL, "")).isEqualTo(1.0);
    }
}
