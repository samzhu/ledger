package io.github.samzhu.ledger.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 用戶日用量聚合文件。
 *
 * <p>記錄單一用戶在特定日期的 LLM API 使用統計，用於：
 * <ul>
 *   <li>用量計費 - 依據 token 用量計算成本</li>
 *   <li>用量分析 - 追蹤用戶使用趨勢</li>
 *   <li>異常偵測 - 識別異常使用模式</li>
 * </ul>
 *
 * <p>文件 ID 格式：{@code {date}_{userId}}，例如 {@code 2025-12-09_user-uuid-123}
 *
 * <p>注意：{@code lastUpdatedAt} 透過 MongoTemplate bulk operations 手動設定，
 * 而非使用 Spring Data 的 auditing 功能，以支援 upsert 操作。
 *
 * @see <a href="https://docs.spring.io/spring-data/mongodb/reference/mongodb/template-update.html">Spring Data MongoDB Update Operations</a>
 */
@Document(collection = "daily_user_usage")
public record DailyUserUsage(
    @Id String id,
    LocalDate date,
    String userId,
    long totalInputTokens,
    long totalOutputTokens,
    long totalCacheCreationTokens,
    long totalCacheReadTokens,
    long totalTokens,
    int requestCount,
    int successCount,
    int errorCount,
    long totalLatencyMs,
    Map<String, ModelBreakdown> modelBreakdown,
    BigDecimal estimatedCostUsd,
    Instant lastUpdatedAt
) {
    /**
     * 模型維度的用量細分。
     *
     * <p>記錄用戶在特定日期使用各個模型的詳細統計，
     * 可用於分析用戶偏好的模型分布。
     *
     * @param inputTokens 輸入 token 數
     * @param outputTokens 輸出 token 數
     * @param requestCount 請求次數
     */
    public record ModelBreakdown(
        long inputTokens,
        long outputTokens,
        int requestCount
    ) {}

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
