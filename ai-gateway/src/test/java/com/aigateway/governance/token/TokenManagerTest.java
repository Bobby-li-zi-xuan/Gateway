package com.aigateway.governance.token;

import com.aigateway.governance.model.TokenStatus;
import com.aigateway.governance.persistence.TokenDao;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H1 单测骨架（对照《版本4-详细实施计划》8.3）。
 * 手敲完成 TokenManager 后删除 @Disabled 即可运行。
 */
@Disabled("TODO H1：手敲 TokenManager 完成后启用")
class TokenManagerTest {

    @Test
    void createThenValidate_plainOnlyOnce() {
        TokenDao dao = mock(TokenDao.class);
        TokenManager mgr = new TokenManager(dao);
        TokenManager.CreateResult r = mgr.create("demo", -1, "COST", "", "", -1);

        assertThat(r.plain()).startsWith("agw_");
        // 明文在库中不存在：库里只有哈希
        verify(dao).insert(argThat(t -> t.tokenHash().equals(TokenManager.sha256(r.plain()))
                && !t.tokenHash().equals(r.plain())));
        // 用明文可校验通过
        assertThat(mgr.validate(r.plain(), "127.0.0.1")).isEqualTo(TokenStatus.VALID);
    }

    @Test
    void validate_rejectsExpiredAndDisabledAndIp() {
        TokenDao dao = mock(TokenDao.class);
        TokenManager mgr = new TokenManager(dao);
        var r = mgr.create("t", -1, "COST", "", "1.2.3.4",
                System.currentTimeMillis() - 1000);   // 已过期

        assertThat(mgr.validate(r.plain(), "1.2.3.4")).isEqualTo(TokenStatus.EXPIRED);

        var r2 = mgr.create("t2", -1, "COST", "", "1.2.3.4", -1);
        when(dao.findAll()).thenReturn(List.of(r2.token()));   // revoke 按 id 查令牌（mock 默认空会先抛 404）
        mgr.revoke(r2.token().id());                  // 吊销
        // 统一语义：吊销 = 内存删除 + DB 标记 revoked_at + 恢复时过滤 → 吊销后查不到 = UNKNOWN（401）
        assertThat(mgr.validate(r2.plain(), "1.2.3.4")).isEqualTo(TokenStatus.UNKNOWN);

        var r3 = mgr.create("t3", -1, "COST", "", "1.2.3.4", -1);
        assertThat(mgr.validate(r3.plain(), "9.9.9.9")).isEqualTo(TokenStatus.IP_DENIED);
    }

    @Test
    void modelScope_limitsAlias() {
        TokenManager mgr = new TokenManager(mock(TokenDao.class));
        var r = mgr.create("t", -1, "COST", "qwen", "", -1);
        assertThat(mgr.modelAllowed(r.token(), "qwen")).isTrue();
        assertThat(mgr.modelAllowed(r.token(), "deepseek-code")).isFalse();
    }
}
