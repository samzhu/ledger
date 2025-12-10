package io.github.samzhu.ledger.dto.api;

import java.time.LocalDate;

/**
 * API 回應中的日期區間。
 *
 * <p>用於表示查詢的時間範圍，包含起始和結束日期。
 *
 * @param start 起始日期（含）
 * @param end 結束日期（含）
 */
public record DatePeriod(
    LocalDate start,
    LocalDate end
) {}
