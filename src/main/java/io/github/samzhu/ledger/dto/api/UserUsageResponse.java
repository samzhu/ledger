package io.github.samzhu.ledger.dto.api;

import java.util.List;

/**
 * 用戶用量查詢 API 回應。
 *
 * <p>包含指定用戶在特定期間的用量統計，用於：
 * <ul>
 *   <li>用戶儀表板 - 顯示個人用量趨勢</li>
 *   <li>計費報表 - 計算用戶應付金額</li>
 * </ul>
 *
 * @param userId 用戶 ID
 * @param period 查詢期間
 * @param summary 期間用量摘要
 * @param daily 每日明細列表
 */
public record UserUsageResponse(
    String userId,
    DatePeriod period,
    UsageSummary summary,
    List<DailyUsage> daily
) {}
