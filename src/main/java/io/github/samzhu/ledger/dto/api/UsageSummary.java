package io.github.samzhu.ledger.dto.api;

import java.math.BigDecimal;

/**
 * API 回應中的用量摘要。
 *
 * <p>彙總指定期間內的 token 用量和成本。
 *
 * @param totalInputTokens 總輸入 token 數
 * @param totalOutputTokens 總輸出 token 數
 * @param totalTokens 總 token 數
 * @param totalRequests 總請求數
 * @param estimatedCostUsd 預估成本（美元）
 */
public record UsageSummary(
    long totalInputTokens,
    long totalOutputTokens,
    long totalTokens,
    int totalRequests,
    BigDecimal estimatedCostUsd
) {}
