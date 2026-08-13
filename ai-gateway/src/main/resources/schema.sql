-- V4 持久化表结构（SchemaInitializer 启动时执行，全部幂等）
-- 对照《版本4-详细实施计划》7.1：渠道 / 令牌 / 预算 / 用量 四张最小集；审计表留 V5。

CREATE TABLE IF NOT EXISTS channel (
    id             VARCHAR(64)  PRIMARY KEY,
    provider       VARCHAR(32),
    base_url       VARCHAR(512),
    credentials_ref VARCHAR(128),
    weight         INT          NOT NULL DEFAULT 1,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    deleted        BOOLEAN      NOT NULL DEFAULT FALSE,  -- 软删除（有历史用量时保护）
    created_at     TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS api_token (
    id             VARCHAR(64)  PRIMARY KEY,             -- agw_ + 8 hex
    token_hash     VARCHAR(64)  NOT NULL UNIQUE,         -- SHA-256 十六进制（凭证只存哈希）
    name           VARCHAR(128),
    quota_limit    DOUBLE       NOT NULL DEFAULT -1,     -- -1 = 无限
    quota_type     VARCHAR(16)  NOT NULL DEFAULT 'COST',
    model_scope    VARCHAR(512) NOT NULL DEFAULT '',
    ip_whitelist   VARCHAR(512) NOT NULL DEFAULT '',
    expires_at     BIGINT       NOT NULL DEFAULT -1,     -- epoch 毫秒；-1 = 永不过期
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP    NOT NULL,
    revoked_at     TIMESTAMP                              -- 吊销时间；NULL = 未吊销
);

CREATE TABLE IF NOT EXISTS budget (
    scope          VARCHAR(16)  NOT NULL,                -- MODEL / CHANNEL / GLOBAL
    scope_value    VARCHAR(128) NOT NULL,                -- MODEL=alias、CHANNEL=channelId、GLOBAL=''
    limit_type     VARCHAR(16)  NOT NULL,                -- TOKEN / COST
    period         VARCHAR(16)  NOT NULL,                -- HOURLY / DAILY / MONTHLY
    limit_value    DOUBLE       NOT NULL,
    used_value     DOUBLE       NOT NULL DEFAULT 0,      -- 对账用的已用量快照（内存计数为主）
    -- 主键保留五维以支持多预算历史，但内存语义同 scope+value 单预算（后写覆盖）；
    -- 已知简化：若需多 period 并存，请同步扩展 BudgetKey（scope+value 一维）
    PRIMARY KEY (scope, scope_value, limit_type, period, limit_value)
);

CREATE TABLE IF NOT EXISTS usage_record (
    request_id     VARCHAR(64)  PRIMARY KEY,
    token_id       VARCHAR(64),
    alias          VARCHAR(64),
    channel_id     VARCHAR(64),
    model          VARCHAR(64),
    token_in       BIGINT       NOT NULL DEFAULT 0,
    token_out      BIGINT       NOT NULL DEFAULT 0,
    cost           DOUBLE       NOT NULL DEFAULT 0,
    latency_ms     BIGINT       NOT NULL DEFAULT 0,
    result         VARCHAR(16)  NOT NULL,                -- success / failure
    created_at     TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_usage_token_time ON usage_record (token_id, created_at);
CREATE INDEX IF NOT EXISTS idx_usage_model_time ON usage_record (model, created_at);
