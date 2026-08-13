package com.aigateway.api.controller;

import com.aigateway.governance.budget.BudgetManager;
import com.aigateway.governance.channel.ChannelRecord;
import com.aigateway.governance.channel.ChannelStore;
import com.aigateway.governance.model.ApiToken;
import com.aigateway.governance.model.Budget;
import com.aigateway.governance.model.BudgetPeriod;
import com.aigateway.governance.model.BudgetScope;
import com.aigateway.governance.model.LimitType;
import com.aigateway.governance.persistence.UsageDao;
import com.aigateway.governance.token.TokenManager;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 内部管理端点（/v1/admin/*）：令牌 / 预算 / 用量 / 渠道的创建与查询
 * （脚手架完整实现，对照《版本4-详细实施计划》15 节）。
 * ⚠️ V5 将整体替换为带鉴权的 Admin API，本版本不设计鉴权（演示与学习用，仅本机部署）。
 */
@RestController
@RequestMapping("/v1/admin")
public class GovernanceAdminController {

    private final TokenManager tokenManager;
    private final BudgetManager budgetManager;
    private final UsageDao usageDao;
    private final ChannelStore channelStore;

    public GovernanceAdminController(TokenManager tokenManager, BudgetManager budgetManager,
                                     UsageDao usageDao, ChannelStore channelStore) {
        this.tokenManager = tokenManager;
        this.budgetManager = budgetManager;
        this.usageDao = usageDao;
        this.channelStore = channelStore;
    }

    // ── 令牌管理 ──

    /** 创建令牌：明文只出现在本次响应（一次性） */
    @PostMapping("/tokens")
    public Map<String, Object> createToken(@RequestBody CreateTokenReq req) {
        TokenManager.CreateResult r = tokenManager.create(req.name(), req.quotaLimit(),
                req.quotaType(), req.modelScope(), req.ipWhitelist(), req.expiresAt());
        return Map.of(
                "token", publicView(r.token()),
                "plain", r.plain());              // ← 明文仅此一次
    }

    /** 令牌列表：脱敏（id / name / 前缀 / 额度 / 状态，无哈希、无明文） */
    @GetMapping("/tokens")
    public List<Map<String, Object>> listTokens() {
        return tokenManager.allTokens().stream()
                .map(this::publicView)
                .toList();
    }

    /** 吊销：删缓存 + 库标记，即时生效 */
    @PostMapping("/tokens/{id}/revoke")
    public Map<String, String> revokeToken(@PathVariable String id) {
        tokenManager.revoke(id);
        return Map.of("result", "ok");
    }

    // ── 预算管理 ──

    @PostMapping("/budgets")
    public Budget createBudget(@RequestBody CreateBudgetReq req) {
        return budgetManager.upsert(BudgetScope.valueOf(req.scope()),
                req.scopeValue(), LimitType.valueOf(req.limitType()),
                BudgetPeriod.valueOf(req.period()), req.limit());
    }

    @GetMapping("/budgets")
    public List<Budget> listBudgets() {
        return budgetManager.allBudgets();
    }

    // ── 用量查询 ──

    /** 按令牌汇总用量与成本（账本查询，演示 A 的“用量记录可查”） */
    @GetMapping("/usage")
    public Map<String, Object> usage(@RequestParam(required = false) String tokenId,
                                     @RequestParam(required = false) String model,
                                     @RequestParam(defaultValue = "7") int days) {
        return usageDao.summarize(tokenId, model, days);
    }

    // ── 渠道管理（16 节）──

    @GetMapping("/channels")
    public List<ChannelRecord> listChannels() { return channelStore.all(); }

    @PostMapping("/channels")
    public ChannelRecord createChannel(@RequestBody CreateChannelReq req) {
        return channelStore.create(req.id(), req.provider(), req.baseUrl(),
                req.credentialsRef(), req.weight());
    }

    @PostMapping("/channels/{id}/status")
    public ChannelRecord setChannelStatus(@PathVariable String id, @RequestBody StatusReq req) {
        return channelStore.setEnabled(id, req.enabled());
    }

    @PostMapping("/channels/{id}/weight")
    public ChannelRecord setChannelWeight(@PathVariable String id, @RequestBody WeightReq req) {
        return channelStore.setWeight(id, req.weight());
    }

    @DeleteMapping("/channels/{id}")
    public Map<String, String> deleteChannel(@PathVariable String id) {
        channelStore.delete(id);                       // 有历史用量 → 软删除
        return Map.of("result", "ok");
    }

    /** 脱敏视图：id / name / 前缀（agw_****）/ 额度 / 状态；不暴露哈希与明文 */
    private Map<String, Object> publicView(ApiToken t) {
        return Map.of(
                "id", t.id(),
                "name", t.name(),
                "display", t.id() + "****",            // 前缀展示（New API 风格）
                "quotaLimit", t.quotaLimit(),
                "quotaType", t.quotaType(),
                "modelScope", t.modelScope(),
                "ipWhitelist", t.ipWhitelist(),
                "expiresAt", t.expiresAt(),
                "enabled", t.enabled());
    }

    // ── DTO（字段名与 JSON 一一对应）──

    public record CreateTokenReq(String name, double quotaLimit, String quotaType,
                                 String modelScope, String ipWhitelist, long expiresAt) {}

    public record CreateBudgetReq(String scope, String scopeValue, String limitType,
                                  String period, double limit) {}

    public record CreateChannelReq(String id, String provider, String baseUrl,
                                   String credentialsRef, int weight) {}

    public record StatusReq(boolean enabled) {}

    public record WeightReq(int weight) {}
}
