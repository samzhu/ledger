package io.github.samzhu.ledger.dto.api;

import java.util.List;

/**
 * 系統整體用量查詢 API 回應。
 *
 * <p>包含整個系統在特定期間的用量統計，用於：
 * <ul>
 *   <li>管理儀表板 - 總覽系統整體使用狀況</li>
 *   <li>成本追蹤 - 監控每日/每月總花費</li>
 *   <li>排行榜 - 顯示高用量用戶和熱門模型</li>
 * </ul>
 *
 * @param period 查詢期間
 * @param summary 期間用量摘要
 * @param daily 每日明細列表
 * @param topUsers 高用量用戶排行
 * @param topModels 熱門模型排行
 */
public record SystemUsageResponse(
    DatePeriod period,
    UsageSummary summary,
    List<DailyUsage> daily,
    List<TopItem> topUsers,
    List<TopItem> topModels
) {
    /**
     * 排行榜項目（用戶或模型）。
     *
     * @param id 識別碼（userId 或 model）
     * @param name 顯示名稱
     * @param requestCount 請求次數
     * @param totalTokens 總 token 數
     */
    public record TopItem(String id, String name, long requestCount, long totalTokens) {}
}
