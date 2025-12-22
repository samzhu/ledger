package io.github.samzhu.ledger.document;

import java.time.Instant;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 月度配額歷史記錄文件。
 *
 * <p>用途：當月份結束時，將 UserQuota 的當期資料歸檔保存，供歷史查詢和分析。
 *
 * <p>設計原則：
 * <ul>
 *   <li>ID 自動生成：由 MongoDB 自動產生 ObjectId</li>
 *   <li>唯一約束：userId + periodYear + periodMonth 組合唯一</li>
 *   <li>歸檔時機：Settlement 處理時檢測到週期變更</li>
 * </ul>
 *
 * @see UserQuota
 */
@Document(collection = "quota_history")
@CompoundIndex(name = "user_period_idx", def = "{'userId': 1, 'periodYear': -1, 'periodMonth': -1}", unique = true)
public record QuotaHistory(
    @Id String id,

    // ========== 基本識別 ==========
    /** 用戶唯一識別碼 */
    String userId,

    // ========== 週期標識 ==========
    /** 歸檔的年份 */
    int periodYear,
    /** 歸檔的月份 (1-12) */
    int periodMonth,

    // ========== 該月統計 ==========
    /** 該月輸入 Token 數 */
    long totalInputTokens,
    /** 該月輸出 Token 數 */
    long totalOutputTokens,
    /** 該月總 Token 數 */
    long totalTokens,
    /** 該月總成本 (USD) */
    double totalCostUsd,
    /** 該月請求次數 */
    int totalRequestCount,

    // ========== 配額資訊（當時設定）==========
    /** 當月基礎配額設定 (USD) */
    double costLimitUsd,
    /** 當月額外額度 (USD) */
    double bonusCostUsd,
    /** 當月有效配額 = costLimitUsd + bonusCostUsd */
    double effectiveLimitUsd,
    /** 月底最終使用率 */
    double finalUsagePercent,
    /** 該月是否曾經超額 */
    boolean wasExceeded,

    // ========== 模型使用分布（分析用）==========
    /** 各模型使用的 Token 數 */
    Map<String, Long> modelTokens,
    /** 各模型產生的成本 */
    Map<String, Double> modelCosts,

    // ========== 時間戳記 ==========
    /** 歸檔執行時間 */
    Instant archivedAt
) {

    /**
     * 從 UserQuota 建立歷史記錄。
     *
     * @param quota 用戶配額
     * @param modelTokens 各模型 Token 分布
     * @param modelCosts 各模型成本分布
     * @return QuotaHistory
     */
    public static QuotaHistory fromUserQuota(
            UserQuota quota,
            Map<String, Long> modelTokens,
            Map<String, Double> modelCosts) {

        return new QuotaHistory(
            null, // ID 自動產生
            quota.userId(),
            quota.periodYear(),
            quota.periodMonth(),
            quota.periodInputTokens(),
            quota.periodOutputTokens(),
            quota.periodTokens(),
            quota.periodCostUsd(),
            quota.periodRequestCount(),
            quota.costLimitUsd(),
            quota.bonusCostUsd(),
            quota.getEffectiveCostLimit(),
            quota.costUsagePercent(),
            quota.quotaExceeded(),
            modelTokens,
            modelCosts,
            Instant.now()
        );
    }

    /**
     * 取得週期的格式化字串。
     *
     * @return 格式如 "2025-12"
     */
    public String getPeriodString() {
        return String.format("%d-%02d", periodYear, periodMonth);
    }
}
