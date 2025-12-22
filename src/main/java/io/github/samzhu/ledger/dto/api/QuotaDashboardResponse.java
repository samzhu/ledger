package io.github.samzhu.ledger.dto.api;

import java.time.Instant;
import java.util.List;

import io.github.samzhu.ledger.document.UserQuota;

/**
 * Quota dashboard API response.
 *
 * <p>Used for GET /api/v1/quota/dashboard endpoint.
 * All monetary values use double for Firestore compatibility.
 * Times are in UTC (Instant).
 */
public record QuotaDashboardResponse(
    Summary summary,
    List<QuotaUserItem> users,
    Instant fetchedAt
) {

    /**
     * Dashboard summary statistics.
     */
    public record Summary(
        int quotaEnabledCount,
        int exceededCount,
        double totalPeriodCostUsd,
        double totalBonusGrantedUsd
    ) {}

    /**
     * Period information for a user.
     */
    public record PeriodInfo(
        int year,
        int month,
        String yearMonth
    ) {}

    /**
     * Quota configuration for a user.
     */
    public record QuotaInfo(
        double baseLimitUsd,
        double bonusUsd,
        double effectiveLimitUsd
    ) {}

    /**
     * Usage information for a user.
     */
    public record UsageInfo(
        double periodCostUsd,
        double usagePercent
    ) {}

    /**
     * Status information for a user.
     */
    public record StatusInfo(
        boolean exceeded,
        String level
    ) {}

    /**
     * User quota item for dashboard display.
     */
    public record QuotaUserItem(
        String userId,
        PeriodInfo period,
        QuotaInfo quota,
        UsageInfo usage,
        StatusInfo status
    ) {
        /**
         * Create from UserQuota document.
         */
        public static QuotaUserItem fromUserQuota(UserQuota q) {
            PeriodInfo period = new PeriodInfo(
                q.periodYear(),
                q.periodMonth(),
                String.format("%d-%02d", q.periodYear(), q.periodMonth())
            );

            QuotaInfo quota = new QuotaInfo(
                q.costLimitUsd(),
                q.bonusCostUsd(),
                q.getEffectiveCostLimit()
            );

            UsageInfo usage = new UsageInfo(
                q.periodCostUsd(),
                q.costUsagePercent()
            );

            StatusInfo status = new StatusInfo(
                q.quotaExceeded(),
                q.getUsageLevel()
            );

            return new QuotaUserItem(q.userId(), period, quota, usage, status);
        }
    }

    /**
     * Create dashboard response from UserQuota list.
     *
     * @param usersWithQuota List of quota-enabled users (already filtered)
     * @return Dashboard response
     */
    public static QuotaDashboardResponse fromUserQuotas(List<UserQuota> usersWithQuota) {
        int quotaEnabledCount = usersWithQuota.size();
        int exceededCount = (int) usersWithQuota.stream()
            .filter(UserQuota::quotaExceeded)
            .count();
        double totalPeriodCost = usersWithQuota.stream()
            .mapToDouble(UserQuota::periodCostUsd)
            .sum();
        double totalBonus = usersWithQuota.stream()
            .mapToDouble(UserQuota::bonusCostUsd)
            .sum();

        Summary summary = new Summary(
            quotaEnabledCount,
            exceededCount,
            totalPeriodCost,
            totalBonus
        );

        List<QuotaUserItem> users = usersWithQuota.stream()
            .map(QuotaUserItem::fromUserQuota)
            .toList();

        return new QuotaDashboardResponse(summary, users, Instant.now());
    }
}
