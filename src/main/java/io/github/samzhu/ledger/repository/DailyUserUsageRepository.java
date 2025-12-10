package io.github.samzhu.ledger.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import io.github.samzhu.ledger.document.DailyUserUsage;

/**
 * 用戶日用量資料存取介面。
 *
 * <p>提供對 {@code daily_user_usage} 集合的 CRUD 操作。
 * 此介面主要用於查詢，寫入操作透過 {@link io.github.samzhu.ledger.service.UsageAggregationService}
 * 使用 MongoTemplate bulk upsert 完成。
 *
 * <p>Spring Data MongoDB 會自動實作此介面，並根據方法名稱產生對應的查詢。
 *
 * @see io.github.samzhu.ledger.document.DailyUserUsage
 * @see <a href="https://docs.spring.io/spring-data/mongodb/reference/mongodb/repositories/query-methods.html">Query Methods</a>
 */
public interface DailyUserUsageRepository extends MongoRepository<DailyUserUsage, String> {

    /**
     * 批次查詢多筆文件。
     *
     * <p>使用 MongoDB {@code $in} 運算子優化批次查詢效能，
     * 適用於一次查詢多個用戶在特定日期區間的用量。
     *
     * @param ids 複合 ID 列表，格式為 {@code YYYY-MM-DD_userId}
     * @return 符合條件的文件列表
     */
    List<DailyUserUsage> findByIdIn(List<String> ids);

    /**
     * 查詢特定用戶的所有日用量，依日期降序排列。
     *
     * <p>用於顯示用戶的歷史用量趨勢，最新日期排在前面。
     *
     * @param userId 用戶 ID
     * @return 該用戶的日用量記錄列表
     */
    List<DailyUserUsage> findByUserIdOrderByDateDesc(String userId);
}
