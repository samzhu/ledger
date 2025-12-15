package io.github.samzhu.ledger.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 用戶配額與累計統計文件。
 *
 * <p>取代原本的 UserSummary，除了累計統計外，還新增配額管理功能：
 * <ul>
 *   <li>累計統計 - 用戶歷史總用量和成本</li>
 *   <li>配額設定 - Token 限制、成本限制、週期類型</li>
 *   <li>週期用量 - 當前週期內的使用統計</li>
 *   <li>配額狀態 - 使用率百分比和超額標記</li>
 *   <li>活動追蹤 - 首次使用和最近活動時間</li>
 * </ul>
 *
 * <p>文件 ID：直接使用 {@code userId}
 *
 * @see <a href="https://docs.spring.io/spring-data/mongodb/reference/mongodb/template-update.html">Spring Data MongoDB Update Operations</a>
 */
@Document(collection = "user_quota")
public record UserQuota(
    @Id String id,
    String userId,

    // === 累計統計（原 UserSummary 欄位）===
    long totalInputTokens,
    long totalOutputTokens,
    long totalTokens,
    int totalRequestCount,
    BigDecimal totalEstimatedCostUsd,

    // === 配額設定 ===
    QuotaConfig quotaConfig,

    // === 當前週期用量 ===
    long periodTokenUsed,
    BigDecimal periodCostUsed,
    int periodRequestCount,

    // === 配額狀態 ===
    double tokenUsagePercent,
    double costUsagePercent,
    boolean quotaExceeded,

    // === 週期資訊 ===
    LocalDate periodStartDate,
    LocalDate periodEndDate,

    // === 活動追蹤 ===
    Instant firstSeenAt,
    Instant lastActiveAt,
    Instant lastUpdatedAt
) {
    /**
     * 配額週期類型。
     */
    public enum QuotaPeriod {
        /** 每日重置 */
        DAILY,
        /** 每週重置 */
        WEEKLY,
        /** 每月重置 */
        MONTHLY
    }

    /**
     * 配額設定。
     *
     * <p>定義用戶的配額限制和週期類型。
     *
     * @param enabled 是否啟用配額限制
     * @param tokenLimit Token 限制，0 表示無限制
     * @param costLimitUsd 成本限制 (USD)，0 表示無限制
     * @param period 配額週期類型
     */
    public record QuotaConfig(
        boolean enabled,
        long tokenLimit,
        BigDecimal costLimitUsd,
        QuotaPeriod period
    ) {
        /**
         * 建立預設配額設定（未啟用）。
         */
        public static QuotaConfig defaults() {
            return new QuotaConfig(false, 0, BigDecimal.ZERO, QuotaPeriod.MONTHLY);
        }

        /**
         * 建立啟用的配額設定。
         *
         * @param tokenLimit Token 限制
         * @param costLimitUsd 成本限制 (USD)
         * @param period 週期類型
         * @return 啟用的配額設定
         */
        public static QuotaConfig enabled(long tokenLimit, BigDecimal costLimitUsd, QuotaPeriod period) {
            return new QuotaConfig(true, tokenLimit, costLimitUsd, period);
        }
    }

    /**
     * 建立新用戶的預設配額文件。
     *
     * @param userId 用戶 ID
     * @return 具有預設設定的新 UserQuota
     */
    public static UserQuota createDefault(String userId) {
        Instant now = Instant.now();
        LocalDate today = LocalDate.now();
        LocalDate periodEnd = today.plusMonths(1);

        return new UserQuota(
            userId,
            userId,
            0, 0, 0, 0, BigDecimal.ZERO,
            QuotaConfig.defaults(),
            0, BigDecimal.ZERO, 0,
            0.0, 0.0, false,
            today, periodEnd,
            now, now, now
        );
    }

    /**
     * 檢查是否需要重置週期。
     *
     * @return 如果當前日期已超過週期結束日期則返回 true
     */
    public boolean shouldResetPeriod() {
        return LocalDate.now().isAfter(periodEndDate);
    }

    /**
     * 計算配額剩餘天數。
     *
     * @return 距離週期結束的天數
     */
    public long getRemainingDays() {
        LocalDate today = LocalDate.now();
        if (today.isAfter(periodEndDate)) {
            return 0;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(today, periodEndDate);
    }

    /**
     * 計算剩餘可用 Token。
     *
     * @return 剩餘 Token 數，如果未啟用配額或無限制則返回 Long.MAX_VALUE
     */
    public long getRemainingTokens() {
        if (!quotaConfig.enabled() || quotaConfig.tokenLimit() == 0) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, quotaConfig.tokenLimit() - periodTokenUsed);
    }

    /**
     * 計算剩餘可用成本額度。
     *
     * @return 剩餘成本額度 (USD)，如果未啟用配額或無限制則返回 null
     */
    public BigDecimal getRemainingCost() {
        if (!quotaConfig.enabled() || quotaConfig.costLimitUsd().compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        BigDecimal remaining = quotaConfig.costLimitUsd().subtract(periodCostUsed);
        return remaining.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : remaining;
    }
}
