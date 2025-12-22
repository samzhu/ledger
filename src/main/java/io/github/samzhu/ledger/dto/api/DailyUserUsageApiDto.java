package io.github.samzhu.ledger.dto.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.samzhu.ledger.document.DailyUserUsage;

/**
 * Dashboard API DTO for DailyUserUsage with ISO 8601 hourly format.
 *
 * <p>Converts internal integer hour keys (0-23) to ISO 8601 format
 * (e.g., "2025-12-22T05:00:00Z") for unambiguous timezone handling.
 */
public record DailyUserUsageApiDto(
    String id,
    LocalDate date,
    String userId,

    // === Token Stats ===
    long totalInputTokens,
    long totalOutputTokens,
    long totalCacheCreationTokens,
    long totalCacheReadTokens,
    long totalTokens,

    // === Request Stats ===
    int requestCount,
    int successCount,
    int errorCount,

    // === Error Breakdown ===
    Map<String, Integer> errorBreakdown,

    // === Latency Stats ===
    DailyUserUsage.LatencyStats latencyStats,

    // === Cache Efficiency ===
    DailyUserUsage.CacheEfficiency cacheEfficiency,

    // === Hourly Distribution (ISO 8601 keys) ===
    Map<String, DailyUserUsage.HourlyBreakdown> hourlyBreakdown,
    String peakHour,
    int peakHourRequests,

    // === Model Breakdown ===
    Map<String, DailyUserUsage.ModelBreakdown> modelBreakdown,

    // === Cost ===
    BigDecimal estimatedCostUsd,
    DailyUserUsage.CostBreakdown costBreakdown,

    Instant lastUpdatedAt
) {
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    /**
     * Creates DTO from DailyUserUsage document.
     */
    public static DailyUserUsageApiDto from(DailyUserUsage usage) {
        LocalDate date = usage.date();

        // Convert hourly integer keys to ISO 8601 format
        Map<String, DailyUserUsage.HourlyBreakdown> isoHourlyBreakdown = new LinkedHashMap<>();
        if (usage.hourlyBreakdown() != null) {
            usage.hourlyBreakdown().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String isoKey = toIsoHour(date, entry.getKey());
                    isoHourlyBreakdown.put(isoKey, entry.getValue());
                });
        }

        // Convert peakHour to ISO format
        String isoPeakHour = toIsoHour(date, usage.peakHour());

        return new DailyUserUsageApiDto(
            usage.id(),
            usage.date(),
            usage.userId(),
            usage.totalInputTokens(),
            usage.totalOutputTokens(),
            usage.totalCacheCreationTokens(),
            usage.totalCacheReadTokens(),
            usage.totalTokens(),
            usage.requestCount(),
            usage.successCount(),
            usage.errorCount(),
            usage.errorBreakdown(),
            usage.latencyStats(),
            usage.cacheEfficiency(),
            isoHourlyBreakdown,
            isoPeakHour,
            usage.peakHourRequests(),
            usage.modelBreakdown(),
            usage.estimatedCostUsd(),
            usage.costBreakdown(),
            usage.lastUpdatedAt()
        );
    }

    /**
     * Converts an integer hour (0-23) and date to ISO 8601 UTC timestamp.
     */
    private static String toIsoHour(LocalDate date, int hour) {
        return date.atTime(LocalTime.of(hour, 0))
            .atOffset(ZoneOffset.UTC)
            .format(ISO_FORMATTER);
    }
}
