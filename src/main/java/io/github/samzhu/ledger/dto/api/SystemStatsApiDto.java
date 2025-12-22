package io.github.samzhu.ledger.dto.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.samzhu.ledger.document.SystemStats;

/**
 * Dashboard API DTO for SystemStats with ISO 8601 hourly format.
 *
 * <p>Converts internal integer hour keys (0-23) to ISO 8601 format
 * (e.g., "2025-12-22T05:00:00Z") for unambiguous timezone handling.
 */
public record SystemStatsApiDto(
    String id,
    LocalDate date,

    // === Global Stats ===
    long totalInputTokens,
    long totalOutputTokens,
    long totalTokens,
    int totalRequestCount,
    int uniqueUsers,
    BigDecimal totalEstimatedCostUsd,

    // === Success Rate ===
    int successCount,
    int errorCount,
    double successRate,

    // === Latency Stats ===
    double avgLatencyMs,
    double p50LatencyMs,
    double p90LatencyMs,
    double p99LatencyMs,

    // === Cache Efficiency ===
    double systemCacheHitRate,
    BigDecimal systemCacheSavedUsd,

    // === Hourly Distribution (ISO 8601 keys) ===
    Map<String, Integer> hourlyRequestCount,
    String peakHour,
    int peakHourRequests,

    // === Leaderboards ===
    List<SystemStats.TopItem> topModels,
    List<SystemStats.TopItem> topUsers,

    Instant lastUpdatedAt
) {
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    /**
     * Creates DTO from SystemStats document.
     */
    public static SystemStatsApiDto from(SystemStats stats) {
        LocalDate date = stats.date();

        // Convert hourly integer keys to ISO 8601 format
        Map<String, Integer> isoHourlyCount = new LinkedHashMap<>();
        if (stats.hourlyRequestCount() != null) {
            stats.hourlyRequestCount().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String isoKey = toIsoHour(date, entry.getKey());
                    isoHourlyCount.put(isoKey, entry.getValue());
                });
        }

        // Convert peakHour to ISO format
        String isoPeakHour = toIsoHour(date, stats.peakHour());

        return new SystemStatsApiDto(
            stats.id(),
            stats.date(),
            stats.totalInputTokens(),
            stats.totalOutputTokens(),
            stats.totalTokens(),
            stats.totalRequestCount(),
            stats.uniqueUsers(),
            stats.totalEstimatedCostUsd(),
            stats.successCount(),
            stats.errorCount(),
            stats.successRate(),
            stats.avgLatencyMs(),
            stats.p50LatencyMs(),
            stats.p90LatencyMs(),
            stats.p99LatencyMs(),
            stats.systemCacheHitRate(),
            stats.systemCacheSavedUsd(),
            isoHourlyCount,
            isoPeakHour,
            stats.peakHourRequests(),
            stats.topModels(),
            stats.topUsers(),
            stats.lastUpdatedAt()
        );
    }

    /**
     * Converts an integer hour (0-23) and date to ISO 8601 UTC timestamp.
     *
     * @param date the date
     * @param hour the hour (0-23)
     * @return ISO 8601 string, e.g., "2025-12-22T05:00:00Z"
     */
    private static String toIsoHour(LocalDate date, int hour) {
        return date.atTime(LocalTime.of(hour, 0))
            .atOffset(ZoneOffset.UTC)
            .format(ISO_FORMATTER);
    }
}
