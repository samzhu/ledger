package io.github.samzhu.ledger.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 用戶日用量聚合文件（增強版）。
 *
 * <p>記錄單一用戶在特定日期的 LLM API 使用統計，包含：
 * <ul>
 *   <li>Token 統計 - 輸入、輸出、Cache 相關 token 數</li>
 *   <li>請求統計 - 成功、失敗、錯誤類型分布</li>
 *   <li>延遲分析 - 平均值和百分位數 (P50/P90/P95/P99)</li>
 *   <li>Cache 效率 - hit rate 和節省成本</li>
 *   <li>時段分析 - 每小時使用分布和尖峰時段</li>
 *   <li>模型分布 - 各模型的使用細分</li>
 *   <li>成本分析 - 總成本和各類別細分</li>
 * </ul>
 *
 * <p>文件 ID 格式：{@code {date}_{userId}}，例如 {@code 2025-12-09_user-uuid-123}
 *
 * @see <a href="https://docs.spring.io/spring-data/mongodb/reference/mongodb/template-update.html">Spring Data MongoDB Update Operations</a>
 */
@Document(collection = "daily_user_usage")
public record DailyUserUsage(
    @Id String id,
    LocalDate date,
    String userId,

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

    // === 錯誤類型分布 ===
    Map<String, Integer> errorBreakdown,

    // === 延遲統計 ===
    LatencyStats latencyStats,
    @JsonIgnore byte[] latencyDigest,  // Internal: T-Digest binary data for percentile calculations

    // === Cache 效率 ===
    CacheEfficiency cacheEfficiency,

    // === 每小時分布 ===
    Map<Integer, HourlyBreakdown> hourlyBreakdown,
    int peakHour,
    int peakHourRequests,

    // === 模型分布 ===
    Map<String, ModelBreakdown> modelBreakdown,

    // === 成本 ===
    BigDecimal estimatedCostUsd,
    CostBreakdown costBreakdown,

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
     * 每小時用量細分。
     *
     * <p>記錄特定小時內的使用統計，用於分析使用高峰。
     *
     * @param requestCount 請求次數
     * @param totalTokens 總 token 數
     * @param costUsd 成本 (USD)
     */
    public record HourlyBreakdown(
        int requestCount,
        long totalTokens,
        BigDecimal costUsd
    ) {
        /**
         * 建立空的小時細分。
         */
        public static HourlyBreakdown empty() {
            return new HourlyBreakdown(0, 0, BigDecimal.ZERO);
        }
    }

    /**
     * 模型維度的用量細分（增強版）。
     *
     * <p>記錄用戶在特定日期使用各個模型的詳細統計。
     *
     * @param inputTokens 輸入 token 數
     * @param outputTokens 輸出 token 數
     * @param cacheReadTokens Cache 讀取 token 數
     * @param requestCount 請求次數
     * @param successCount 成功次數
     * @param errorCount 失敗次數
     * @param costUsd 成本 (USD)
     */
    public record ModelBreakdown(
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        int requestCount,
        int successCount,
        int errorCount,
        BigDecimal costUsd
    ) {
        /**
         * 建立空的模型細分。
         */
        public static ModelBreakdown empty() {
            return new ModelBreakdown(0, 0, 0, 0, 0, 0, BigDecimal.ZERO);
        }
    }

    /**
     * 成本細分。
     *
     * <p>將總成本拆分為各類別，便於分析成本結構。
     *
     * @param inputCost 輸入 token 成本 (USD)
     * @param outputCost 輸出 token 成本 (USD)
     * @param cacheReadCost Cache 讀取成本 (USD)
     * @param cacheWriteCost Cache 寫入成本 (USD)
     */
    public record CostBreakdown(
        BigDecimal inputCost,
        BigDecimal outputCost,
        BigDecimal cacheReadCost,
        BigDecimal cacheWriteCost
    ) {
        /**
         * 建立空的成本細分。
         */
        public static CostBreakdown empty() {
            return new CostBreakdown(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        /**
         * 計算總成本。
         */
        public BigDecimal total() {
            return inputCost.add(outputCost).add(cacheReadCost).add(cacheWriteCost);
        }
    }

    /**
     * 產生複合主鍵。
     *
     * <p>使用日期和用戶 ID 組合，確保每個用戶每天只有一筆聚合記錄。
     *
     * @param date 日期
     * @param userId 用戶 ID
     * @return 複合 ID，格式為 {@code YYYY-MM-DD_userId}
     */
    public static String createId(LocalDate date, String userId) {
        return date.toString() + "_" + userId;
    }
}
