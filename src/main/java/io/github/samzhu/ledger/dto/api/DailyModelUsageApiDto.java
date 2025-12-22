package io.github.samzhu.ledger.dto.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.samzhu.ledger.document.DailyModelUsage;

/**
 * Dashboard API DTO for DailyModelUsage with ISO 8601 hourly format.
 *
 * <p>Converts internal integer hour keys (0-23) to ISO 8601 format
 * (e.g., "2025-12-22T05:00:00Z") for unambiguous timezone handling.
 */
public record DailyModelUsageApiDto(
    String id,
    LocalDate date,
    String model,

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
    int uniqueUsers,

    // === Error Breakdown ===
    Map<String, Integer> errorBreakdown,

    // === Latency Stats ===
    DailyModelUsage.LatencyStats latencyStats,

    // === Cache Efficiency ===
    DailyModelUsage.CacheEfficiency cacheEfficiency,

    // === Hourly Distribution (ISO 8601 keys) ===
    Map<String, Integer> hourlyRequestCount,
    String peakHour,
    int peakHourRequests,

    // === Cost ===
    BigDecimal estimatedCostUsd,

    Instant lastUpdatedAt
) {
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    /**
     * Creates DTO from DailyModelUsage document.
     */
    public static DailyModelUsageApiDto from(DailyModelUsage usage) {
        LocalDate date = usage.date();

        // Convert hourly integer keys to ISO 8601 format
        Map<String, Integer> isoHourlyCount = new LinkedHashMap<>();
        if (usage.hourlyRequestCount() != null) {
            usage.hourlyRequestCount().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String isoKey = toIsoHour(date, entry.getKey());
                    isoHourlyCount.put(isoKey, entry.getValue());
                });
        }

        // Convert peakHour to ISO format
        String isoPeakHour = toIsoHour(date, usage.peakHour());

        return new DailyModelUsageApiDto(
            usage.id(),
            usage.date(),
            usage.model(),
            usage.totalInputTokens(),
            usage.totalOutputTokens(),
            usage.totalCacheCreationTokens(),
            usage.totalCacheReadTokens(),
            usage.totalTokens(),
            usage.requestCount(),
            usage.successCount(),
            usage.errorCount(),
            usage.uniqueUsers(),
            usage.errorBreakdown(),
            usage.latencyStats(),
            usage.cacheEfficiency(),
            isoHourlyCount,
            isoPeakHour,
            usage.peakHourRequests(),
            usage.estimatedCostUsd(),
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
