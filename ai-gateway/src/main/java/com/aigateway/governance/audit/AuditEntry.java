package com.aigateway.governance.audit;

/** 审计记录：谁、什么时候、改了什么、结果 */
public record AuditEntry(
    long id,                //自增主键（DB生成）
    String opType,          // CONFIG_APPLY / CONFIG_ROLLBACK / TOKEN_CREATE / TOKEN_REVOKE / TOKEN_QUOTA / CHANNEL_DELETE ...
    String operator,        // gateway.admin.name
    String target,          // 目标对象：config / token / channel
    String detail,          // 变更摘要：ConfigDiff
    String result,
    String error,
    long creaqtedAt){

    }

