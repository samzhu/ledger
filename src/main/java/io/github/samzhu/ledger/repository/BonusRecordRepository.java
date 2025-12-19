package io.github.samzhu.ledger.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import io.github.samzhu.ledger.document.BonusRecord;

/**
 * 額外額度記錄資料存取介面。
 *
 * <p>提供對 {@code bonus_records} 集合的查詢操作。
 * 記錄為只讀資料，建立後不會修改，用於稽核追蹤。
 *
 * @see io.github.samzhu.ledger.document.BonusRecord
 */
public interface BonusRecordRepository extends MongoRepository<BonusRecord, String> {

    /**
     * 查詢用戶所有額外額度記錄，按建立時間降序排列（用於稽核追蹤）。
     *
     * @param userId 用戶唯一識別碼
     * @return 額外額度記錄清單
     */
    List<BonusRecord> findByUserIdOrderByCreatedAtDesc(String userId);

    /**
     * 分頁查詢用戶額外額度記錄（用於管理介面）。
     *
     * @param userId 用戶唯一識別碼
     * @param pageable 分頁參數
     * @return 分頁結果
     */
    Page<BonusRecord> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    /**
     * 查詢用戶特定月份的額外額度記錄（查看該月給予的所有額度）。
     *
     * @param userId 用戶唯一識別碼
     * @param periodYear 年份
     * @param periodMonth 月份 (1-12)
     * @return 該月額外額度記錄清單
     */
    List<BonusRecord> findByUserIdAndPeriodYearAndPeriodMonth(
            String userId, int periodYear, int periodMonth);

    // ========== 統計查詢 ==========

    /**
     * 統計用戶特定月份的額外額度給予次數。
     *
     * @param userId 用戶唯一識別碼
     * @param periodYear 年份
     * @param periodMonth 月份 (1-12)
     * @return 給予次數
     */
    long countByUserIdAndPeriodYearAndPeriodMonth(
            String userId, int periodYear, int periodMonth);
}
