package io.github.samzhu.ledger.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import io.github.samzhu.ledger.document.RawEventBatch;

/**
 * 批次原始事件資料存取介面。
 *
 * <p>提供對 {@code raw_event_batches} 集合的 CRUD 操作。
 * 寫入操作由 {@link io.github.samzhu.ledger.service.EventBufferService} 在
 * flush 時執行，每批最多儲存 {@code ledger.batch.size} 個事件。
 *
 * <p>此資料主要用於：
 * <ul>
 *   <li>批次結算 - 查詢未處理的批次進行聚合統計</li>
 *   <li>資料重算 - 若聚合邏輯變更，可重新處理原始事件</li>
 *   <li>稽核 - 保留完整的事件記錄</li>
 * </ul>
 *
 * @see io.github.samzhu.ledger.document.RawEventBatch
 * @see <a href="https://docs.spring.io/spring-data/mongodb/reference/mongodb/repositories/query-methods.html">Query Methods</a>
 */
public interface RawEventBatchRepository extends MongoRepository<RawEventBatch, String> {

    /**
     * 查詢尚未結算的批次，依建立時間排序。
     *
     * <p>用於定時結算任務，取得需要聚合處理的批次。
     *
     * @return 未結算的批次列表，依建立時間升序排列
     */
    List<RawEventBatch> findByProcessedFalseOrderByCreatedAtAsc();
}
