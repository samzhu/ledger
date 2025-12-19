package io.github.samzhu.ledger.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import io.github.samzhu.ledger.document.QuotaHistory;

/**
 * 月度配額歷史記錄資料存取介面。
 *
 * <p>提供對 {@code quota_history} 集合的查詢操作。
 * 歷史記錄為只讀資料，建立後不會修改。
 *
 * @see io.github.samzhu.ledger.document.QuotaHistory
 */
public interface QuotaHistoryRepository extends MongoRepository<QuotaHistory, String> {

    // ========== 查詢方法 ==========

    /**
     * 查詢用戶所有歷史配額記錄，按年月降序排列（最新的在前）。
     *
     * @param userId 用戶唯一識別碼
     * @return 歷史記錄清單
     */
    List<QuotaHistory> findByUserIdOrderByPeriodYearDescPeriodMonthDesc(String userId);

    /**
     * 分頁查詢用戶歷史配額記錄（用於歷史記錄頁面）。
     *
     * @param userId 用戶唯一識別碼
     * @param pageable 分頁參數
     * @return 分頁結果
     */
    Page<QuotaHistory> findByUserIdOrderByPeriodYearDescPeriodMonthDesc(String userId, Pageable pageable);

    /**
     * 查詢用戶特定月份的歷史記錄。
     *
     * @param userId 用戶唯一識別碼
     * @param periodYear 年份
     * @param periodMonth 月份 (1-12)
     * @return 該月歷史記錄（如存在）
     */
    Optional<QuotaHistory> findByUserIdAndPeriodYearAndPeriodMonth(
            String userId, int periodYear, int periodMonth);

    /**
     * 檢查用戶特定月份是否已有歷史記錄（避免重複歸檔）。
     *
     * @param userId 用戶唯一識別碼
     * @param periodYear 年份
     * @param periodMonth 月份 (1-12)
     * @return true 表示該月已有歸檔記錄
     */
    boolean existsByUserIdAndPeriodYearAndPeriodMonth(
            String userId, int periodYear, int periodMonth);

    // ========== 按年份查詢 ==========

    /**
     * 查詢用戶特定年份的所有月度記錄（用於年度報表）。
     *
     * @param userId 用戶唯一識別碼
     * @param periodYear 年份
     * @return 該年各月歷史記錄，按月份降序
     */
    List<QuotaHistory> findByUserIdAndPeriodYearOrderByPeriodMonthDesc(String userId, int periodYear);

    // ========== 統計查詢 ==========

    /**
     * 統計用戶歷史記錄總數（了解用戶使用月數）。
     *
     * @param userId 用戶唯一識別碼
     * @return 歷史記錄筆數
     */
    long countByUserId(String userId);

    /**
     * 統計用戶歷史記錄總數（@Query 寫法範例）。
     *
     * @param userId 用戶唯一識別碼
     * @return 歷史記錄筆數
     */
    @Query(value = "{ 'userId': ?0 }", count = true)
    long countHistoryByUserId(String userId);
}
