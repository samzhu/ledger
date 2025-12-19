package io.github.samzhu.ledger.dto.api;

import java.math.BigDecimal;
import java.time.Instant;

import io.github.samzhu.ledger.document.UserQuota;
import io.github.samzhu.ledger.util.PeriodUtils;

/**
 * 配額狀態 API 回應。
 *
 * <p>用於 GET /api/v1/quota/users/{userId} 端點。
 */
public record QuotaStatusResponse(
    String id,
    String userId,
    PeriodInfo period,
    QuotaInfo quota,
    UsageInfo usage,
    StatusInfo status,
    BonusInfo bonus,
    TotalsInfo totals
) {

    /**
     * 週期資訊。
     */
    public record PeriodInfo(
        String yearMonth,
        Instant startAt,
        Instant endAt,
        long daysRemaining
    ) {}

    /**
     * 配額設定資訊。
     */
    public record QuotaInfo(
        boolean enabled,
        BigDecimal baseLimitUsd,
        BigDecimal bonusUsd,
        BigDecimal effectiveLimitUsd
    ) {}

    /**
     * 當期用量資訊。
     */
    public record UsageInfo(
        BigDecimal costUsd,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        int requestCount
    ) {}

    /**
     * 配額狀態資訊。
     */
    public record StatusInfo(
        double usagePercent,
        BigDecimal remainingUsd,
        boolean exceeded,
        String level
    ) {}

    /**
     * 額外額度資訊。
     */
    public record BonusInfo(
        BigDecimal amount,
        String reason,
        Instant grantedAt
    ) {}

    /**
     * 歷史累計資訊。
     */
    public record TotalsInfo(
        long allTimeTokens,
        BigDecimal allTimeCostUsd,
        long allTimeRequests
    ) {}

    /**
     * 從 UserQuota 建立回應物件。
     */
    public static QuotaStatusResponse fromUserQuota(UserQuota quota) {
        PeriodInfo period = new PeriodInfo(
            PeriodUtils.formatPeriod(quota.periodYear(), quota.periodMonth()),
            quota.periodStartAt(),
            quota.periodEndAt(),
            PeriodUtils.getDaysRemaining(quota.periodEndAt())
        );

        QuotaInfo quotaInfo = new QuotaInfo(
            quota.quotaEnabled(),
            quota.costLimitUsd(),
            quota.bonusCostUsd(),
            quota.getEffectiveCostLimit()
        );

        UsageInfo usage = new UsageInfo(
            quota.periodCostUsd(),
            quota.periodInputTokens(),
            quota.periodOutputTokens(),
            quota.periodTokens(),
            quota.periodRequestCount()
        );

        StatusInfo status = new StatusInfo(
            quota.costUsagePercent(),
            quota.getRemainingCost(),
            quota.quotaExceeded(),
            quota.getUsageLevel()
        );

        BonusInfo bonus = null;
        if (quota.bonusCostUsd() != null && quota.bonusCostUsd().compareTo(BigDecimal.ZERO) > 0) {
            bonus = new BonusInfo(
                quota.bonusCostUsd(),
                quota.bonusReason(),
                quota.bonusGrantedAt()
            );
        }

        TotalsInfo totals = new TotalsInfo(
            quota.totalTokens(),
            quota.totalEstimatedCostUsd(),
            quota.totalRequestCount()
        );

        return new QuotaStatusResponse(
            quota.id(),
            quota.userId(),
            period,
            quotaInfo,
            usage,
            status,
            bonus,
            totals
        );
    }
}
