package io.github.samzhu.ledger.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

import io.github.samzhu.ledger.document.UserQuota;

/**
 * 用戶配額與累計統計資料存取介面。
 *
 * <p>提供對 {@code user_quota} 集合的 CRUD 操作。
 *
 * <p>更新操作策略：
 * <ul>
 *   <li>使用 {@code @Query + @Update} 進行原子更新</li>
 *   <li>支援 {@code $inc} 原子增量操作，避免併發問題</li>
 *   <li>寫入操作透過 MongoTemplate 或 Repository 完成</li>
 * </ul>
 *
 * @see io.github.samzhu.ledger.document.UserQuota
 * @see <a href="https://docs.spring.io/spring-data/mongodb/reference/mongodb/repositories/query-methods.html">Query Methods</a>
 */
public interface UserQuotaRepository extends MongoRepository<UserQuota, String> {

    // ========== 基本查詢 (Derived Query Methods) ==========

    /**
     * 根據用戶 ID 查詢配額記錄。
     *
     * @param userId 用戶唯一識別碼
     * @return 用戶配額記錄（如存在）
     */
    Optional<UserQuota> findByUserId(String userId);

    /**
     * 檢查用戶是否已有配額記錄。
     *
     * @param userId 用戶唯一識別碼
     * @return true 表示已存在配額記錄
     */
    boolean existsByUserId(String userId);

    /**
     * 查詢所有啟用配額限制的用戶。
     *
     * @return 啟用配額的用戶清單
     */
    List<UserQuota> findByQuotaEnabledTrue();

    /**
     * 查詢所有已超額的用戶（用於管理介面告警）。
     *
     * @return 已超額的用戶清單
     */
    List<UserQuota> findByQuotaExceededTrue();

    /**
     * 查詢指定週期的所有用戶配額（用於報表統計）。
     *
     * @param periodYear 年份
     * @param periodMonth 月份 (1-12)
     * @return 該週期的用戶配額清單
     */
    List<UserQuota> findByPeriodYearAndPeriodMonth(int periodYear, int periodMonth);

    // ========== 分頁查詢 ==========

    /**
     * 查詢用量排行榜。
     *
     * @param pageable 分頁參數
     * @return 依用量排序的用戶配額列表
     */
    List<UserQuota> findAllByOrderByTotalTokensDesc(Pageable pageable);

    /**
     * 分頁查詢所有用戶，按最後活動時間降序排列（用於管理介面）。
     *
     * @param pageable 分頁參數
     * @return 分頁結果
     */
    Page<UserQuota> findAllByOrderByLastActiveAtDesc(Pageable pageable);

    /**
     * 分頁查詢啟用配額的用戶，按使用率降序排列（用於監控高用量用戶）。
     *
     * @param pageable 分頁參數
     * @return 分頁結果
     */
    Page<UserQuota> findByQuotaEnabledTrueOrderByCostUsagePercentDesc(Pageable pageable);

    // ========== 複合條件查詢 (@Query) ==========

    /**
     * 查詢已啟用配額且已超額的用戶（需要關注的用戶）。
     *
     * @return 已超額的用戶清單
     */
    @Query("{ 'quotaEnabled': true, 'quotaExceeded': true }")
    List<UserQuota> findEnabledAndExceeded();

    /**
     * 查詢使用率達到指定閾值的用戶（用於預警通知）。
     *
     * @param percent 使用率閾值（如 80.0 表示 80%）
     * @return 達到閾值的用戶清單
     */
    @Query("{ 'quotaEnabled': true, 'costUsagePercent': { '$gte': ?0 } }")
    List<UserQuota> findByUsagePercentGreaterThanEqual(double percent);

    // ========== 更新操作 (@Query + @Update) ==========

    /**
     * 累加用量（單一用戶）。
     *
     * <p>使用 $inc 原子操作符，同時更新當期用量和總計。
     *
     * @param userId 用戶 ID
     * @param inputTokens 輸入 Token 增量
     * @param outputTokens 輸出 Token 增量
     * @param totalTokens 總 Token 增量
     * @param cost 成本增量 (USD)
     * @param requestCount 請求次數增量
     * @param now 當前時間
     * @return 更新的文件數
     */
    @Query("{ 'userId': ?0 }")
    @Update("{ '$inc': { " +
            "'periodInputTokens': ?1, 'periodOutputTokens': ?2, 'periodTokens': ?3, " +
            "'periodCostUsd': ?4, 'periodRequestCount': ?5, " +
            "'totalInputTokens': ?1, 'totalOutputTokens': ?2, 'totalTokens': ?3, " +
            "'totalEstimatedCostUsd': ?4, 'totalRequestCount': ?5 " +
            "}, '$set': { 'lastActiveAt': ?6, 'lastUpdatedAt': ?6 } }")
    long incrementUsageByUserId(String userId,
            long inputTokens, long outputTokens, long totalTokens,
            BigDecimal cost, int requestCount, Instant now);

    /**
     * 更新配額狀態（使用率和超額標記）。
     *
     * @param userId 用戶 ID
     * @param usagePercent 使用率百分比
     * @param exceeded 是否超額
     * @param now 當前時間
     * @return 更新的文件數
     */
    @Query("{ 'userId': ?0 }")
    @Update("{ '$set': { 'costUsagePercent': ?1, 'quotaExceeded': ?2, 'lastUpdatedAt': ?3 } }")
    long updateQuotaStatusByUserId(String userId, double usagePercent, boolean exceeded, Instant now);

    /**
     * 累加額外額度。
     *
     * @param userId 用戶 ID
     * @param amount 額度金額
     * @param reason 給予原因
     * @param now 當前時間
     * @return 更新的文件數
     */
    @Query("{ 'userId': ?0 }")
    @Update("{ '$inc': { 'bonusCostUsd': ?1 }, '$set': { 'bonusReason': ?2, 'bonusGrantedAt': ?3, 'lastUpdatedAt': ?3 } }")
    long addBonusByUserId(String userId, BigDecimal amount, String reason, Instant now);

    /**
     * 重置週期並設定初始用量（用於跨月時歸檔後重置）。
     *
     * <p>重要：此方法會重置當期用量和額外額度，同時累加到總計。
     *
     * @param userId 用戶 ID
     * @param year 新週期年份
     * @param month 新週期月份
     * @param periodStart 週期開始時間
     * @param periodEnd 週期結束時間
     * @param inputTokens 初始輸入 Token
     * @param outputTokens 初始輸出 Token
     * @param totalTokens 初始總 Token
     * @param cost 初始成本
     * @param requestCount 初始請求次數
     * @param now 當前時間
     * @return 更新的文件數
     */
    @Query("{ 'userId': ?0 }")
    @Update("{ '$set': { " +
            "'periodYear': ?1, 'periodMonth': ?2, 'periodStartAt': ?3, 'periodEndAt': ?4, " +
            "'periodInputTokens': ?5, 'periodOutputTokens': ?6, 'periodTokens': ?7, " +
            "'periodCostUsd': ?8, 'periodRequestCount': ?9, " +
            "'bonusCostUsd': 0, 'bonusReason': null, 'bonusGrantedAt': null, " +
            "'costUsagePercent': 0.0, 'quotaExceeded': false, " +
            "'lastActiveAt': ?10, 'lastUpdatedAt': ?10 " +
            "}, '$inc': { " +
            "'totalInputTokens': ?5, 'totalOutputTokens': ?6, 'totalTokens': ?7, " +
            "'totalEstimatedCostUsd': ?8, 'totalRequestCount': ?9 " +
            "} }")
    long resetPeriodAndSetUsageByUserId(String userId, int year, int month,
            Instant periodStart, Instant periodEnd,
            long inputTokens, long outputTokens, long totalTokens,
            BigDecimal cost, int requestCount, Instant now);

    /**
     * 設定配額上限。
     *
     * @param userId 用戶 ID
     * @param enabled 是否啟用配額
     * @param limitUsd 成本上限 (USD)
     * @param now 當前時間
     * @return 更新的文件數
     */
    @Query("{ 'userId': ?0 }")
    @Update("{ '$set': { 'quotaEnabled': ?1, 'costLimitUsd': ?2, 'lastUpdatedAt': ?3 } }")
    long updateQuotaSettingsByUserId(String userId, boolean enabled, BigDecimal limitUsd, Instant now);
}
