package io.github.samzhu.ledger.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import io.github.samzhu.ledger.document.DailyModelUsage;

/**
 * 模型日用量資料存取介面。
 *
 * <p>提供對 {@code daily_model_usage} 集合的 CRUD 操作。
 * 此介面主要用於查詢，寫入操作透過 {@link io.github.samzhu.ledger.service.UsageAggregationService}
 * 使用 MongoTemplate bulk upsert 完成。
 *
 * <p>Spring Data MongoDB 會自動實作此介面，並根據方法名稱產生對應的查詢。
 *
 * @see io.github.samzhu.ledger.document.DailyModelUsage
 * @see <a href="https://docs.spring.io/spring-data/mongodb/reference/mongodb/repositories/query-methods.html">Query Methods</a>
 */
public interface DailyModelUsageRepository extends MongoRepository<DailyModelUsage, String> {

    /**
     * 批次查詢多筆文件。
     *
     * <p>使用 MongoDB {@code $in} 運算子優化批次查詢效能，
     * 適用於一次查詢多個模型在特定日期區間的用量。
     *
     * @param ids 複合 ID 列表，格式為 {@code YYYY-MM-DD_model}
     * @return 符合條件的文件列表
     */
    List<DailyModelUsage> findByIdIn(List<String> ids);

    /**
     * 查詢特定模型的所有日用量，依日期降序排列。
     *
     * <p>用於顯示模型的歷史用量趨勢，最新日期排在前面。
     *
     * @param model 模型名稱（例如 {@code claude-sonnet-4-20250514}）
     * @return 該模型的日用量記錄列表
     */
    List<DailyModelUsage> findByModelOrderByDateDesc(String model);
}
