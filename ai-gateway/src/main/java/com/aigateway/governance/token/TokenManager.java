package com.aigateway.governance.token;

import com.aigateway.core.exception.GatewayException;
import com.aigateway.governance.model.ApiToken;
import com.aigateway.governance.model.TokenStatus;
import com.aigateway.governance.persistence.TokenDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌管理器：凭证的唯一守门人
 *
 * 设计语义：
 * 1. 创建时生成随机明文 token，**只返回一次**；库中只存 SHA-256 哈希——
 *    数据库泄露也拿不到可用凭证（密码存储思路）；
 * 2. 校验走「查缓存 → 逐条核对」的时序，全部通过才算 VALID；
 *    模型范围与额度预检需要请求上下文（alias / 预估用量），由 GovernanceService
 *    在校验后另行执行（本类只做令牌自身的检查）；
 * 3. 吊销 = 删缓存 + 库标记，即时生效；已发出的在途请求不追溯（New API 语义）；
 * 4. 缓存是热路径（读多写少），DB 是慢路径；校验失败不写缓存（防脏条目）。
 */
@Component
public class TokenManager {

    private static final Logger log = LoggerFactory.getLogger(TokenManager.class);
    /** 明文前缀：htl_ 便于日志识别与脱敏展示（htl_****） */
    public static final String PREFIX = "htl_";

    private final TokenDao dao;
    /** tokenHash -> ApiToken（热路径缓存；吊销时删除） */
    private final Map<String, ApiToken> cache = new ConcurrentHashMap<>();

    public TokenManager(TokenDao dao) {
        this.dao = dao;
        warmup();
    }

    /** 启动预热：全量加载令牌到缓存 */
    private void warmup(){
        for(ApiToken t : dao.findAll()){
            cache.put(t.tokenHash(), t);
        }
        log.info("令牌缓存预热完成，共 {} 个", cache.size());
    }

    /**
     * 创建令牌：生成明文 → 哈希入库 → 返回（明文只在返回值里出现一次）。
     * @return record CreateResult(ApiToken token, String plain) —— 明文只此一份
     */
    public CreateResult create(String name, double quotaLimit, String quotaType,
                               String modelScope, String ipWhitelist, long expiresAt) {
        String id = PREFIX + randomHex(8);
        String plain = PREFIX + randomHex(32);      //明文凭证
        ApiToken token = new ApiToken(id, name, sha256(plain), quotaLimit,
                quotaType, modelScope == null ? "" : modelScope,
                ipWhitelist == null ? "" : ipWhitelist,
                expiresAt, true, System.currentTimeMillis());
        dao.insert(token);
        cache.put(token.tokenHash(), token);
        log.info("令牌创建 id={} name={}（明文不再可见）", id, name);
        return new CreateResult(token, plain);
    }

    /** 创建结果：token 是管理对象（可查列表），plain 是唯一一次明文 */
    public record CreateResult(ApiToken token, String plain) {}

    /**
     * 校验一个原始令牌：只查令牌自身的状态（存在/启用/过期/IP）。
     * 模型范围与额度预检由调用方（GovernanceService）基于返回结果继续。
     */
    public TokenStatus validate(String plainToken, String clientIp) {
        // 取令牌 → sha256 → 查缓存（未命中兜底 dao.findByHash）→
        // 逐条核对 enabled / expiresAt / IP 白名单 → 返回 TokenStatus
        // （校验失败不写缓存，防脏条目；令牌不存在返回 UNKNOWN）
        if(plainToken == null || plainToken.isBlank()){
            return TokenStatus.MISSING;
        }
        String hash = sha256(plainToken.trim());
        ApiToken t = cache.get(hash);
        if(t == null){
            // 缓存未命中兜底查库：仍然没有 -> 不存在
            t = dao.findByHash(hash);
            if(t == null){
                return TokenStatus.UNKNOWN;
            }
            cache.put(hash, t);
        }
        if(!t.enabled()){
            return TokenStatus.DISABLED;
        }
        if(t.expiresAt() >= 0 && System.currentTimeMillis() > t.expiresAt()){
            return TokenStatus.EXPIRED;
        }
        if(!ipAllowed(t, clientIp)){
            return TokenStatus.IP_DENIED;
        }
        return TokenStatus.VALID;
    }


    /**
     * 校验通过则返回令牌对象（GovernanceFilter 构造限流 key 用）。
     * 内部复用 validate 的状态检查，避免两处校验逻辑漂移。
     */
    public Optional<ApiToken> resolve(String plainToken, String clientIp) {
        if (plainToken == null || plainToken.isBlank()) {
            return Optional.empty();
        }
        TokenStatus status = validate(plainToken, clientIp);
        if (status != TokenStatus.VALID) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.get(sha256(plainToken.trim())));
    }

    /** 吊销：删缓存 + 库标记（在途请求不追溯） */
    public void revoke(String tokenId) {
        ApiToken t = dao.findAll().stream()
                .filter(x -> x.id().equals(tokenId)).findFirst().orElse(null);
        if (t == null) {
            throw new GatewayException(404, "token_not_found", "令牌不存在: " + tokenId);
        }
        dao.revoke(tokenId);
        cache.remove(t.tokenHash());
        log.info("令牌吊销 id={}", tokenId);
    }

    /** 按管理 ID 查令牌（管理端点/脱敏列表用） */
    public Optional<ApiToken> findById(String tokenId) {
        return cache.values().stream().filter(t -> t.id().equals(tokenId)).findFirst()
                .or(() -> dao.findAll().stream().filter(t -> t.id().equals(tokenId)).findFirst());
    }

    /** 全部令牌（脱敏列表：只暴露 id / name / 前缀，不暴露哈希与明文） */
    public List<ApiToken> allTokens() {
        return List.copyOf(cache.values());
    }

    /** 模型范围校验：modelScope 为空放行，否则 alias 必须在列表中 */
    public boolean modelAllowed(ApiToken token, String alias) {
        if (token.modelScope() == null || token.modelScope().isBlank()) return true;
        return Arrays.stream(token.modelScope().split(","))
                .map(String::trim).anyMatch(alias::equals);
    }

    /** IP 白名单：空放行；否则精确匹配（本版本不做 CIDR，文档标注） */
    private static boolean ipAllowed(ApiToken token, String clientIp) {
        if (token.ipWhitelist() == null || token.ipWhitelist().isBlank()) return true;
        if (clientIp == null) return false;
        return Arrays.stream(token.ipWhitelist().split(","))
                .map(String::trim).anyMatch(clientIp::equals);
    }

    /** SHA-256 十六进制（凭证只存哈希） */
    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 随机十六进制串（SecureRandom 保证不可预测） */
    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        new SecureRandom().nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
