package com.aigateway.governance.channel;

import com.aigateway.core.exception.GatewayException;
import com.aigateway.governance.persistence.ChannelDao;
import com.aigateway.infra.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渠道存储：内存缓存（原子替换引用）+ DB 持久化（脚手架完整实现，对照 16 节）。
 *
 * 启动合并规则：model.yml 渠道作为种子写库（不存在才插入），
 * DB 中同 id 的 weight / enabled 覆盖配置值——运行期改过的不被重启冲掉
 * （⚠️ 该机制是 V4 内部实现细节，V5 将移除改配置权威，见 V5 计划 2.1）。
 * 变更生效：所有写操作先改内存（ConcurrentHashMap 原子替换）再落库，
 * 在途请求读到的要么是旧引用要么是新引用，不会读到半改状态。
 */
@Component
public class ChannelStore {

    private final ChannelDao dao;
    private final Map<String, ChannelRecord> channels = new ConcurrentHashMap<>();

    public ChannelStore(ChannelDao dao, GatewayProperties props) {
        this.dao = dao;
        seedFromConfig(props);           // 配置种子入库（不存在才插入）
        loadFromDb();                    // DB 覆盖同 id 的 weight / enabled
    }

    /** 创建渠道（id 冲突报 409）；候选要引用它需重启加载（文档标注） */
    public ChannelRecord create(String id, String provider, String baseUrl,
                                String credentialsRef, int weight) {
        if (channels.containsKey(id)) {
            throw new GatewayException(409, "channel_exists", "渠道已存在: " + id);
        }
        ChannelRecord rec = new ChannelRecord(id, provider, baseUrl,
                credentialsRef, weight, true, false, System.currentTimeMillis());
        channels.put(id, rec);
        dao.insert(rec);
        return rec;
    }

    /** 启停：即时生效（AvailabilityFilter 消费 isEnabled） */
    public ChannelRecord setEnabled(String id, boolean enabled) {
        ChannelRecord old = require(id);
        ChannelRecord next = old.withEnabled(enabled);
        channels.put(id, next);          // 原子替换：在途请求读旧值，新请求读新值
        dao.updateStatus(id, enabled);
        return next;
    }

    public ChannelRecord setWeight(String id, int weight) {
        ChannelRecord old = require(id);
        ChannelRecord next = old.withWeight(weight);
        channels.put(id, next);
        dao.updateWeight(id, weight);
        return next;
    }

    /** 删除：有用量记录 → 软删除（deleted=true），否则物理删除 */
    public void delete(String id) {
        ChannelRecord old = require(id);
        if (dao.hasChannelUsage(id)) {
            channels.put(id, old.withDeleted(true));
            dao.softDelete(id);
        } else {
            channels.remove(id);
            dao.delete(id);
        }
    }

    /** 启停判定：存在且启用且未软删除（AvailabilityFilter 一行接入） */
    public boolean isEnabled(String id) {
        ChannelRecord rec = channels.get(id);
        return rec != null && rec.enabled() && !rec.deleted();
    }

    public Optional<ChannelRecord> find(String id) { return Optional.ofNullable(channels.get(id)); }

    public List<ChannelRecord> all() {
        return channels.values().stream()
                .filter(r -> !r.deleted())
                .toList();
    }

    /** 启动合并：model.yml 渠道作为种子入库（不存在才插入），DB 状态不覆盖配置种子 */
    private void seedFromConfig(GatewayProperties props) {
        for (GatewayProperties.ChannelDef def : props.getChannels()) {
            if (channels.containsKey(def.getId())) continue;
            ChannelRecord rec = new ChannelRecord(def.getId(), def.getProvider(), def.getBaseUrl(),
                    def.getCredentialsRef(), def.getWeight(), true, false,
                    System.currentTimeMillis());
            try {
                dao.insert(rec);
                channels.put(def.getId(), rec);
            } catch (Exception e) {
                // 已存在（并发/重复启动）或其它 DB 异常：以 DB 为准，从库加载
                channels.putIfAbsent(def.getId(), rec);
            }
        }
    }

    /** 启动加载：DB 覆盖同 id 的 weight / enabled（运行期改过的不被重启冲掉） */
    private void loadFromDb() {
        for (ChannelRecord rec : dao.findAll()) {
            channels.put(rec.id(), rec);
        }
    }

    private ChannelRecord require(String id) {
        ChannelRecord rec = channels.get(id);
        if (rec == null) {
            throw new GatewayException(404, "channel_not_found", "渠道不存在: " + id);
        }
        return rec;
    }
}
