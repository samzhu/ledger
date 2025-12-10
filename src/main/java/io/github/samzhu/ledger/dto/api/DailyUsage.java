package io.github.samzhu.ledger.dto.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * API 回應中的單日用量資料。
 *
 * <p>用於時間序列圖表或每日明細表格。
 *
 * @param date 日期
 * @param inputTokens 輸入 token 數
 * @param outputTokens 輸出 token 數
 * @param totalTokens 總 token 數
 * @param requests 請求數
 * @param costUsd 當日成本（美元）
 */
public record DailyUsage(
    LocalDate date,
    long inputTokens,
    long outputTokens,
    long totalTokens,
    int requests,
    BigDecimal costUsd
) {}
