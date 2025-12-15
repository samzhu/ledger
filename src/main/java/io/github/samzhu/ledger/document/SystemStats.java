package io.github.samzhu.ledger.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 系統日統計文件（簡化重新設計版）。
 *
 * <p>記錄整個系統在特定日期的 LLM API 使用統計，包含：
 * <ul>
 *   <li>全域統計 - 總 token、請求數、獨立用戶數</li>
 *   <li>成功率分析 - 成功/失敗次數和比率</li>
 *   <li>延遲統計 - 平均和百分位延遲</li>
 *   <li>Cache 效率 - 系統層級的 cache 效率</li>
 *   <li>時段分析 - 每小時請求分布和尖峰時段</li>
 *   <li>排行榜 - 熱門模型和活躍用戶</li>
 * </ul>
 *
 * <p>文件 ID：日期字串，格式為 {@code YYYY-MM-DD}
 *
 * @see <a href="https://docs.spring.io/spring-data/mongodb/reference/mongodb/template-update.html">Spring Data MongoDB Update Operations</a>
 */
@Document(collection = "system_stats")
public record SystemStats(
    @Id String id,
    LocalDate date,

    // === 全域統計 ===
    long totalInputTokens,
    long totalOutputTokens,
    long totalTokens,
    int totalRequestCount,
    int uniqueUsers,
    BigDecimal totalEstimatedCostUsd,

    // === 成功率 ===
    int successCount,
    int errorCount,
    double successRate,

    // === 延遲統計 ===
    double avgLatencyMs,
    double p50LatencyMs,
    double p90LatencyMs,
    double p99LatencyMs,

    // === Cache 效率 ===
    double systemCacheHitRate,
    BigDecimal systemCacheSavedUsd,

    // === 每小時分布 ===
    Map<Integer, Integer> hourlyRequestCount,
    int peakHour,
    int peakHourRequests,

    // === 排行榜 ===
    List<TopItem> topModels,
    List<TopItem> topUsers,

    Instant lastUpdatedAt
) {
    /**
     * 排行榜項目。
     *
     * <p>用於記錄熱門模型或活躍用戶的統計。
     *
     * @param id 識別碼（userId 或 model 名稱）
     * @param requestCount 請求次數
     * @param totalTokens 總 token 數
     * @param costUsd 成本 (USD)
     */
    public record TopItem(
        String id,
        int requestCount,
        long totalTokens,
        BigDecimal costUsd
    ) {}

    /**
     * 使用日期作為文件 ID。
     *
     * @param date 日期
     * @return 日期字串，格式為 {@code YYYY-MM-DD}
     */
    public static String createId(LocalDate date) {
        return date.toString();
    }
}
