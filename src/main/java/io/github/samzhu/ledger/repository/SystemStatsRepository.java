package io.github.samzhu.ledger.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import io.github.samzhu.ledger.document.SystemStats;

/**
 * 系統日統計資料存取介面。
 *
 * <p>提供對 {@code system_stats} 集合的 CRUD 操作。
 * 此介面主要用於查詢，寫入操作透過 {@link io.github.samzhu.ledger.service.UsageAggregationService}
 * 使用 MongoTemplate bulk upsert 完成。
 *
 * <p>Spring Data MongoDB 會自動實作此介面，並根據方法名稱產生對應的查詢。
 *
 * @see io.github.samzhu.ledger.document.SystemStats
 * @see <a href="https://docs.spring.io/spring-data/mongodb/reference/mongodb/repositories/query-methods.html">Query Methods</a>
 */
public interface SystemStatsRepository extends MongoRepository<SystemStats, String> {

    /**
     * 批次查詢多個日期的系統統計。
     *
     * <p>使用 MongoDB {@code $in} 運算子優化批次查詢效能，
     * 適用於一次查詢多個日期的系統整體用量。
     *
     * @param ids 日期字串列表，格式為 {@code YYYY-MM-DD}
     * @return 符合條件的系統統計列表
     */
    List<SystemStats> findByIdIn(List<String> ids);
}
