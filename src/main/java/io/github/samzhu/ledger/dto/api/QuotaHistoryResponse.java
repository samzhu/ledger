package io.github.samzhu.ledger.dto.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.samzhu.ledger.document.QuotaHistory;
import io.github.samzhu.ledger.util.PeriodUtils;

/**
 * 配額歷史 API 回應。
 *
 * <p>用於 GET /api/v1/quota/users/{userId}/history 端點。
 */
public record QuotaHistoryResponse(
    String userId,
    List<HistoryItem> history
) {

    /**
     * 歷史記錄項目。
     */
    public record HistoryItem(
        String id,
        int periodYear,
        int periodMonth,
        String periodString,
        UsageDetail usage,
        QuotaDetail quota,
        double finalUsagePercent,
        boolean wasExceeded,
        Map<String, ModelDetail> modelBreakdown,
        Instant archivedAt
    ) {}

    /**
     * 用量詳情。
     */
    public record UsageDetail(
        long totalTokens,
        BigDecimal totalCostUsd,
        int requestCount
    ) {}

    /**
     * 配額詳情。
     */
    public record QuotaDetail(
        BigDecimal limitUsd,
        BigDecimal bonusUsd,
        BigDecimal effectiveLimitUsd
    ) {}

    /**
     * 模型使用詳情。
     */
    public record ModelDetail(
        long tokens,
        BigDecimal costUsd
    ) {}

    /**
     * 從 QuotaHistory 列表建立回應物件。
     */
    public static QuotaHistoryResponse fromHistoryList(String userId, List<QuotaHistory> histories) {
        List<HistoryItem> items = histories.stream()
            .map(h -> new HistoryItem(
                h.id(),
                h.periodYear(),
                h.periodMonth(),
                PeriodUtils.formatPeriod(h.periodYear(), h.periodMonth()),
                new UsageDetail(
                    h.totalTokens(),
                    h.totalCostUsd(),
                    h.totalRequestCount()
                ),
                new QuotaDetail(
                    h.costLimitUsd(),
                    h.bonusCostUsd(),
                    h.effectiveLimitUsd()
                ),
                h.finalUsagePercent(),
                h.wasExceeded(),
                buildModelBreakdown(h),
                h.archivedAt()
            ))
            .toList();

        return new QuotaHistoryResponse(userId, items);
    }

    private static Map<String, ModelDetail> buildModelBreakdown(QuotaHistory h) {
        if (h.modelTokens() == null || h.modelCosts() == null) {
            return Map.of();
        }

        return h.modelTokens().entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                e -> new ModelDetail(
                    e.getValue(),
                    h.modelCosts().getOrDefault(e.getKey(), BigDecimal.ZERO)
                )
            ));
    }
}
