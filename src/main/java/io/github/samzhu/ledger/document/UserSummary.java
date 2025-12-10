package io.github.samzhu.ledger.document;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 用戶累計統計文件。
 *
 * <p>記錄單一用戶自註冊以來的累計 LLM API 使用統計，用於：
 * <ul>
 *   <li>用戶配額管理 - 控制用戶的總用量上限</li>
 *   <li>用戶價值分析 - 識別高價值用戶</li>
 *   <li>活躍度追蹤 - 記錄用戶首次和最近使用時間</li>
 * </ul>
 *
 * <p>文件 ID：直接使用 {@code userId}
 *
 * <p>注意：{@code lastUpdatedAt} 透過 MongoTemplate bulk operations 手動設定，
 * 而非使用 Spring Data 的 auditing 功能，以支援 upsert 操作。
 *
 * @see <a href="https://docs.spring.io/spring-data/mongodb/reference/mongodb/template-update.html">Spring Data MongoDB Update Operations</a>
 */
@Document(collection = "user_summary")
public record UserSummary(
    @Id String id,
    String userId,
    long totalInputTokens,
    long totalOutputTokens,
    long totalTokens,
    int totalRequestCount,
    BigDecimal totalEstimatedCostUsd,
    Instant firstSeenAt,
    Instant lastActiveAt,
    Instant lastUpdatedAt
) {
}
