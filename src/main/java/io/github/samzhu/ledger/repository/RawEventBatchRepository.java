package io.github.samzhu.ledger.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import io.github.samzhu.ledger.document.RawEventBatch;

/**
 * 批次原始事件資料存取介面。
 *
 * <p>提供對 {@code raw_event_batches} 集合的 CRUD 操作。
 * 寫入操作由 {@link io.github.samzhu.ledger.service.EventBufferService} 在
 * flush 時執行，每批最多儲存 100 個事件。
 *
 * <p>此資料主要用於：
 * <ul>
 *   <li>問題追蹤 - 查詢特定時間範圍的原始事件</li>
 *   <li>資料重算 - 若聚合邏輯變更，可重新處理原始事件</li>
 *   <li>稽核 - 保留完整的事件記錄</li>
 * </ul>
 *
 * @see io.github.samzhu.ledger.document.RawEventBatch
 * @see <a href="https://docs.spring.io/spring-data/mongodb/reference/mongodb/repositories/query-methods.html">Query Methods</a>
 */
public interface RawEventBatchRepository extends MongoRepository<RawEventBatch, String> {

    /**
     * 查詢特定日期的所有批次。
     *
     * <p>用於查詢某一天的所有原始事件。
     *
     * @param date 要查詢的日期
     * @return 該日期的批次列表
     */
    List<RawEventBatch> findByDate(LocalDate date);

    /**
     * 查詢日期區間內的批次，依建立時間排序。
     *
     * <p>用於匯出或重新處理特定期間的原始事件。
     *
     * @param startDate 起始日期（含）
     * @param endDate 結束日期（含）
     * @return 符合條件的批次列表，依建立時間升序排列
     */
    List<RawEventBatch> findByDateBetweenOrderByCreatedAtAsc(LocalDate startDate, LocalDate endDate);
}
