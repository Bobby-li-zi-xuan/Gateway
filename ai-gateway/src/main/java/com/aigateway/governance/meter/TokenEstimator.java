package com.aigateway.governance.meter;

import com.aigateway.api.dto.ChatRequest;

/**
 * 文本 → token 近似估算（公共估算器，从 Scorer 抽取，口径与 mock 服务端一致）：
 * CJK 字符约 cjkTokenPerChar 字/token（默认 1），其余约 charsPerToken 字符/token（默认 4）。
 * V4 起 Scorer / CapabilityFilter / TokenMeter / BudgetFilter 统一复用本类，消除口径漂移。
 */
public final class TokenEstimator {

    /** 缺省系数（与 GovernanceProperties.estimation 一致；Scorer 等无治理配置处用） */
    public static final double DEFAULT_CHARS_PER_TOKEN = 4.0;
    public static final double DEFAULT_CJK_TOKEN_PER_CHAR = 1.0;

    private TokenEstimator() {}

    public static long estimateTextTokens(String text, double charsPerToken, double cjkTokenPerChar) {
        long cjk = 0, other = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) cjk++;
            else other++;
        }
        return (long) (cjk * cjkTokenPerChar) + (long) Math.ceil(other / charsPerToken);
    }

    public static long estimateInputTokens(ChatRequest request,
                                           double charsPerToken, double cjkTokenPerChar) {
        if (request.messages() == null) return 1;
        long tokens = request.messages().stream()
                .map(ChatRequest.Message::content)
                .filter(s -> s != null)
                .mapToLong(s -> estimateTextTokens(s, charsPerToken, cjkTokenPerChar))
                .sum();
        return Math.max(1, tokens);
    }
}
