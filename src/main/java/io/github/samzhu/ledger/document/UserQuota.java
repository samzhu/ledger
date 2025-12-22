package io.github.samzhu.ledger.document;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 用戶配額與累計統計文件。
 *
 * <p>設計原則：
 * <ul>
 *   <li>ID 自動生成：所有 Document 的 {@code _id} 由 MongoDB 自動產生 ObjectId</li>
 *   <li>避免自定義類型：不使用自定義 Class、Record、Enum（如 YearMonth、QuotaConfig）</li>
 *   <li>年月標識：使用 {@code periodYear} (int) + {@code periodMonth} (int) 標識週期</li>
 *   <li>統一月度週期：以月為單位管理配額</li>
 *   <li>USD 為主：配額以美元設定</li>
 * </ul>
 *
 * <p>欄位分類：
 * <ul>
 *   <li>累計統計 - 用戶歷史總用量和成本（永不重置）</li>
 *   <li>配額設定 - 是否啟用、成本限制</li>
 *   <li>週期用量 - 當前週期內的使用統計（每月重置）</li>
 *   <li>額外額度 - 管理員給予的額外配額（每月重置）</li>
 *   <li>配額狀態 - 使用率百分比和超額標記</li>
 *   <li>時間戳記 - 活動追蹤</li>
 * </ul>
 *
 * @see <a href="https://docs.spring.io/spring-data/mongodb/reference/mongodb/template-update.html">Spring Data MongoDB Update Operations</a>
 */
@Document(collection = "user_quota")
public record UserQuota(
    @Id String id,

    // ========== 基本識別 ==========
    /** 用戶唯一識別碼，對應 Gate 的 user ID */
    @Indexed(unique = true) String userId,

    // ========== 週期標識 ==========
    /** 當前配額週期的年份 */
    int periodYear,
    /** 當前配額週期的月份 (1-12) */
    int periodMonth,
    /** 週期開始時間 (UTC)，該月 1 號 00:00:00 */
    Instant periodStartAt,
    /** 週期結束時間 (UTC)，該月最後一天 23:59:59.999 */
    Instant periodEndAt,

    // ========== 累計統計（全歷史，不重置）==========
    /** 歷史累計輸入 Token 數 */
    long totalInputTokens,
    /** 歷史累計輸出 Token 數 */
    long totalOutputTokens,
    /** 歷史累計總 Token 數 */
    long totalTokens,
    /** 歷史累計請求次數 */
    long totalRequestCount,
    /** 歷史累計成本 (USD) */
    double totalEstimatedCostUsd,

    // ========== 配額設定（管理員設定）==========
    /** 是否啟用配額限制，false = 無限制 */
    boolean quotaEnabled,
    /** 月度成本上限 (USD)，0 = 無限制 */
    double costLimitUsd,

    // ========== 當期用量（每月重置）==========
    /** 當月輸入 Token 數 */
    long periodInputTokens,
    /** 當月輸出 Token 數 */
    long periodOutputTokens,
    /** 當月總 Token 數 */
    long periodTokens,
    /** 當月已使用成本 (USD) */
    double periodCostUsd,
    /** 當月請求次數 */
    int periodRequestCount,

    // ========== 額外額度（每月重置）==========
    /** 管理員額外給予的 USD 額度 */
    double bonusCostUsd,
    /** 給予額外額度的原因 */
    String bonusReason,
    /** 最後一次給予額外額度的時間 */
    Instant bonusGrantedAt,

    // ========== 配額狀態（計算欄位）==========
    /** 成本使用率 = periodCostUsd / (costLimitUsd + bonusCostUsd) * 100 */
    double costUsagePercent,
    /** 是否已超額 (costUsagePercent >= 100) */
    boolean quotaExceeded,

    // ========== 時間戳記 ==========
    /** 用戶首次出現時間 */
    Instant firstSeenAt,
    /** 用戶最後活動時間 */
    Instant lastActiveAt,
    /** 文件最後更新時間 */
    Instant lastUpdatedAt
) {

    /**
     * 取得有效成本上限（基礎配額 + 額外額度）。
     *
     * @return 有效成本上限 (USD)
     */
    public double getEffectiveCostLimit() {
        return costLimitUsd + bonusCostUsd;
    }

    /**
     * 計算剩餘可用成本額度。
     *
     * @return 剩餘成本額度 (USD)，如果未啟用配額或無限制則返回 null
     */
    public Double getRemainingCost() {
        if (!quotaEnabled || getEffectiveCostLimit() == 0) {
            return null;
        }
        double remaining = getEffectiveCostLimit() - periodCostUsd;
        return remaining < 0 ? 0.0 : remaining;
    }

    /**
     * 計算配額使用等級（用於 UI 顏色顯示）。
     *
     * @return 使用等級：OK, WARNING, CRITICAL, EXCEEDED
     */
    public String getUsageLevel() {
        if (costUsagePercent >= 100) return "EXCEEDED";
        if (costUsagePercent >= 80) return "CRITICAL";
        if (costUsagePercent >= 50) return "WARNING";
        return "OK";
    }

    /**
     * 計算成本使用率。
     *
     * @param periodCost 當期使用成本
     * @param effectiveLimit 有效成本上限
     * @return 使用率百分比 (0-100+)
     */
    public static double calculateCostUsagePercent(double periodCost, double effectiveLimit) {
        if (effectiveLimit <= 0) {
            return 0.0;
        }
        return (periodCost / effectiveLimit) * 100.0;
    }

    /**
     * 建立新用戶的預設配額文件（Builder pattern）。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * UserQuota Builder。
     */
    public static class Builder {
        private String id;
        private String userId;
        private int periodYear;
        private int periodMonth;
        private Instant periodStartAt;
        private Instant periodEndAt;
        private long totalInputTokens;
        private long totalOutputTokens;
        private long totalTokens;
        private long totalRequestCount;
        private double totalEstimatedCostUsd;
        private boolean quotaEnabled;
        private double costLimitUsd;
        private long periodInputTokens;
        private long periodOutputTokens;
        private long periodTokens;
        private double periodCostUsd;
        private int periodRequestCount;
        private double bonusCostUsd;
        private String bonusReason;
        private Instant bonusGrantedAt;
        private double costUsagePercent;
        private boolean quotaExceeded;
        private Instant firstSeenAt;
        private Instant lastActiveAt;
        private Instant lastUpdatedAt;

        public Builder id(String id) { this.id = id; return this; }
        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder periodYear(int periodYear) { this.periodYear = periodYear; return this; }
        public Builder periodMonth(int periodMonth) { this.periodMonth = periodMonth; return this; }
        public Builder periodStartAt(Instant periodStartAt) { this.periodStartAt = periodStartAt; return this; }
        public Builder periodEndAt(Instant periodEndAt) { this.periodEndAt = periodEndAt; return this; }
        public Builder totalInputTokens(long totalInputTokens) { this.totalInputTokens = totalInputTokens; return this; }
        public Builder totalOutputTokens(long totalOutputTokens) { this.totalOutputTokens = totalOutputTokens; return this; }
        public Builder totalTokens(long totalTokens) { this.totalTokens = totalTokens; return this; }
        public Builder totalRequestCount(long totalRequestCount) { this.totalRequestCount = totalRequestCount; return this; }
        public Builder totalEstimatedCostUsd(double totalEstimatedCostUsd) { this.totalEstimatedCostUsd = totalEstimatedCostUsd; return this; }
        public Builder quotaEnabled(boolean quotaEnabled) { this.quotaEnabled = quotaEnabled; return this; }
        public Builder costLimitUsd(double costLimitUsd) { this.costLimitUsd = costLimitUsd; return this; }
        public Builder periodInputTokens(long periodInputTokens) { this.periodInputTokens = periodInputTokens; return this; }
        public Builder periodOutputTokens(long periodOutputTokens) { this.periodOutputTokens = periodOutputTokens; return this; }
        public Builder periodTokens(long periodTokens) { this.periodTokens = periodTokens; return this; }
        public Builder periodCostUsd(double periodCostUsd) { this.periodCostUsd = periodCostUsd; return this; }
        public Builder periodRequestCount(int periodRequestCount) { this.periodRequestCount = periodRequestCount; return this; }
        public Builder bonusCostUsd(double bonusCostUsd) { this.bonusCostUsd = bonusCostUsd; return this; }
        public Builder bonusReason(String bonusReason) { this.bonusReason = bonusReason; return this; }
        public Builder bonusGrantedAt(Instant bonusGrantedAt) { this.bonusGrantedAt = bonusGrantedAt; return this; }
        public Builder costUsagePercent(double costUsagePercent) { this.costUsagePercent = costUsagePercent; return this; }
        public Builder quotaExceeded(boolean quotaExceeded) { this.quotaExceeded = quotaExceeded; return this; }
        public Builder firstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; return this; }
        public Builder lastActiveAt(Instant lastActiveAt) { this.lastActiveAt = lastActiveAt; return this; }
        public Builder lastUpdatedAt(Instant lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; return this; }

        public UserQuota build() {
            return new UserQuota(
                id, userId,
                periodYear, periodMonth, periodStartAt, periodEndAt,
                totalInputTokens, totalOutputTokens, totalTokens, totalRequestCount, totalEstimatedCostUsd,
                quotaEnabled, costLimitUsd,
                periodInputTokens, periodOutputTokens, periodTokens, periodCostUsd, periodRequestCount,
                bonusCostUsd, bonusReason, bonusGrantedAt,
                costUsagePercent, quotaExceeded,
                firstSeenAt, lastActiveAt, lastUpdatedAt
            );
        }
    }
}
