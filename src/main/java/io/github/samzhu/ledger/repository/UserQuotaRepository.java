package io.github.samzhu.ledger.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import io.github.samzhu.ledger.document.UserQuota;

/**
 * 用戶配額與累計統計資料存取介面。
 *
 * <p>提供對 {@code user_quota} 集合的 CRUD 操作。
 * 此介面主要用於查詢，寫入操作透過
 * {@link io.github.samzhu.ledger.service.UsageAggregationService}
 * 使用 MongoTemplate bulk upsert 完成。
 *
 * <p>Spring Data MongoDB 會自動實作此介面，並根據方法名稱產生對應的查詢。
 *
 * @see io.github.samzhu.ledger.document.UserQuota
 * @see <a href="https://docs.spring.io/spring-data/mongodb/reference/mongodb/repositories/query-methods.html">Query Methods</a>
 */
public interface UserQuotaRepository extends MongoRepository<UserQuota, String> {

    /**
     * 查詢用量排行榜。
     *
     * <p>依總 token 用量降序排列，用於產生「高用量用戶」排行榜。
     * 使用 {@link Pageable} 參數支援分頁，避免一次載入過多資料。
     *
     * @param pageable 分頁參數（可指定 page 和 size）
     * @return 依用量排序的用戶配額列表
     */
    List<UserQuota> findAllByOrderByTotalTokensDesc(Pageable pageable);

    /**
     * 查詢已啟用配額的用戶。
     *
     * @return 已啟用配額的用戶列表
     */
    List<UserQuota> findByQuotaConfigEnabledTrue();

    /**
     * 查詢已超額的用戶。
     *
     * @return 已超額的用戶列表
     */
    List<UserQuota> findByQuotaExceededTrue();

    /**
     * 查詢已啟用配額且已超額的用戶。
     *
     * @return 已啟用配額且已超額的用戶列表
     */
    List<UserQuota> findByQuotaConfigEnabledTrueAndQuotaExceededTrue();

    /**
     * 依最近活動時間排序查詢用戶。
     *
     * @param pageable 分頁參數
     * @return 依最近活動時間降序排列的用戶列表
     */
    List<UserQuota> findAllByOrderByLastActiveAtDesc(Pageable pageable);
}
