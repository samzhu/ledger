package io.github.samzhu.ledger.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 模型日用量聚合文件。
 *
 * <p>記錄單一 LLM 模型在特定日期的使用統計，用於：
 * <ul>
 *   <li>成本分析 - 各模型的花費比較</li>
 *   <li>容量規劃 - 預測各模型的使用趨勢</li>
 *   <li>效能監控 - 追蹤各模型的成功率和延遲</li>
 * </ul>
 *
 * <p>文件 ID 格式：{@code {date}_{model}}，例如 {@code 2025-12-09_claude-sonnet-4-20250514}
 *
 * <p>注意：{@code lastUpdatedAt} 透過 MongoTemplate bulk operations 手動設定，
 * 而非使用 Spring Data 的 auditing 功能，以支援 upsert 操作。
 *
 * @see <a href="https://docs.spring.io/spring-data/mongodb/reference/mongodb/template-update.html">Spring Data MongoDB Update Operations</a>
 */
@Document(collection = "daily_model_usage")
public record DailyModelUsage(
    @Id String id,
    LocalDate date,
    String model,
    long totalInputTokens,
    long totalOutputTokens,
    long totalCacheCreationTokens,
    long totalCacheReadTokens,
    long totalTokens,
    int requestCount,
    int successCount,
    int errorCount,
    int uniqueUsers,
    long avgLatencyMs,
    BigDecimal estimatedCostUsd,
    Instant lastUpdatedAt
) {
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
