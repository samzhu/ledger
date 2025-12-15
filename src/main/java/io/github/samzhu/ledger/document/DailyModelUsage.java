package io.github.samzhu.ledger.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 模型日用量聚合文件（增強版）。
 *
 * <p>記錄單一 LLM 模型在特定日期的使用統計，包含：
 * <ul>
 *   <li>Token 統計 - 輸入、輸出、Cache 相關 token 數</li>
 *   <li>請求統計 - 成功、失敗、錯誤類型分布、獨立用戶數</li>
 *   <li>延遲分析 - 平均值和百分位數 (P50/P90/P95/P99)</li>
 *   <li>Cache 效率 - hit rate 和節省成本</li>
 *   <li>時段分析 - 每小時請求分布和尖峰時段</li>
 *   <li>成本分析 - 模型總成本</li>
 * </ul>
 *
 * <p>文件 ID 格式：{@code {date}_{model}}，例如 {@code 2025-12-09_claude-sonnet-4-20250514}
 *
 * @see <a href="https://docs.spring.io/spring-data/mongodb/reference/mongodb/template-update.html">Spring Data MongoDB Update Operations</a>
 */
@Document(collection = "daily_model_usage")
public record DailyModelUsage(
    @Id String id,
    LocalDate date,
    String model,

    // === Token 統計 ===
    long totalInputTokens,
    long totalOutputTokens,
    long totalCacheCreationTokens,
    long totalCacheReadTokens,
    long totalTokens,

    // === 請求統計 ===
    int requestCount,
    int successCount,
    int errorCount,
    int uniqueUsers,

    // === 錯誤類型分布 ===
    Map<String, Integer> errorBreakdown,

    // === 延遲統計 ===
    LatencyStats latencyStats,
    byte[] latencyDigest,

    // === Cache 效率 ===
    CacheEfficiency cacheEfficiency,

    // === 每小時分布 ===
    Map<Integer, Integer> hourlyRequestCount,
    int peakHour,
    int peakHourRequests,

    // === 成本 ===
    BigDecimal estimatedCostUsd,

    Instant lastUpdatedAt
) {
    /**
     * 延遲統計資料。
     *
     * <p>包含延遲的基本統計和百分位數，使用 T-Digest 演算法計算。
     *
     * @param totalLatencyMs 總延遲 (毫秒)
     * @param minLatencyMs 最小延遲 (毫秒)
     * @param maxLatencyMs 最大延遲 (毫秒)
     * @param avgLatencyMs 平均延遲 (毫秒)
     * @param p50Ms P50 延遲 (毫秒)
     * @param p90Ms P90 延遲 (毫秒)
     * @param p95Ms P95 延遲 (毫秒)
     * @param p99Ms P99 延遲 (毫秒)
     */
    public record LatencyStats(
        long totalLatencyMs,
        long minLatencyMs,
        long maxLatencyMs,
        double avgLatencyMs,
        double p50Ms,
        double p90Ms,
        double p95Ms,
        double p99Ms
    ) {
        /**
         * 建立空的延遲統計。
         */
        public static LatencyStats empty() {
            return new LatencyStats(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /**
     * Cache 效率指標。
     *
     * <p>追蹤 Prompt Cache 的使用效率和節省的成本。
     *
     * @param hitRate Cache 命中率 (0.0 - 1.0)
     * @param tokensSaved 因 Cache 節省的 token 重新計算數
     * @param costSaved 因 Cache 節省的成本 (USD)
     */
    public record CacheEfficiency(
        double hitRate,
        long tokensSaved,
        BigDecimal costSaved
    ) {
        /**
         * 建立空的 Cache 效率指標。
         */
        public static CacheEfficiency empty() {
            return new CacheEfficiency(0.0, 0, BigDecimal.ZERO);
        }
    }

    /**
     * 產生複合主鍵。
     *
     * <p>使用日期和模型名稱組合，確保每個模型每天只有一筆聚合記錄。
     *
     * @param date 日期
     * @param model 模型名稱（例如 {@code claude-sonnet-4-20250514}）
     * @return 複合 ID，格式為 {@code YYYY-MM-DD_model}
     */
    public static String createId(LocalDate date, String model) {
        return date.toString() + "_" + model;
    }
}
