package com.aigateway.governance.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** 预算周期：到期自动重置（惰性重置见 H3） */
public enum BudgetPeriod {
    HOURLY, DAILY, MONTHLY;

    /** 当前周期标识：HOURLY=yyyyMMddHH、DAILY=yyyyMMdd、MONTHLY=yyyyMM（周期切换判据） */
    public String periodId(long nowMs) {
        ZonedDateTime t = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(nowMs), ZoneId.systemDefault());
        return switch (this) {
            case HOURLY -> t.format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
            case DAILY -> t.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            case MONTHLY -> t.format(DateTimeFormatter.ofPattern("yyyyMM"));
        };
    }
}
