package io.github.samzhu.ledger.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 系統日統計文件。
 *
 * <p>記錄整個系統在特定日期的 LLM API 使用統計，用於：
 * <ul>
 *   <li>營運儀表板 - 顯示每日系統整體用量</li>
 *   <li>成本追蹤 - 計算每日總花費</li>
 *   <li>排行榜 - 記錄當日熱門模型和活躍用戶</li>
 * </ul>
 *
 * <p>文件 ID：日期字串，格式為 {@code YYYY-MM-DD}
 *
 * <p>注意：{@code lastUpdatedAt} 透過 MongoTemplate bulk operations 手動設定，
 * 而非使用 Spring Data 的 auditing 功能，以支援 upsert 操作。
 *
 * @see <a href="https://docs.spring.io/spring-data/mongodb/reference/mongodb/template-update.html">Spring Data MongoDB Update Operations</a>
 */
@Document(collection = "system_stats")
public record SystemStats(
    @Id String id,
    LocalDate date,
    long totalInputTokens,
    long totalOutputTokens,
    long totalTokens,
    int totalRequestCount,
    int uniqueUsers,
    BigDecimal totalEstimatedCostUsd,
    List<TopModel> topModels,
    List<TopUser> topUsers,
    Instant lastUpdatedAt
) {
    /**
     * 熱門模型記錄，依請求數排序。
     *
     * @param model 模型名稱
     * @param requestCount 請求次數
     */
    public record TopModel(String model, int requestCount) {}

    /**
     * 活躍用戶記錄，依請求數排序。
     *
     * @param userId 用戶 ID
     * @param requestCount 請求次數
     */
    public record TopUser(String userId, int requestCount) {}
}
