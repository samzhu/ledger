package io.github.samzhu.ledger.document;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import io.github.samzhu.ledger.dto.UsageEventData;

/**
 * 批次原始事件文件。
 *
 * <p>將多個用量事件批次儲存為單一文件，這是針對 Firestore/MongoDB 的成本優化策略：
 * <ul>
 *   <li>減少寫入次數 - 100 個事件只需 1 次寫入（而非 100 次）</li>
 *   <li>降低儲存成本 - 寫入費用是最貴的操作</li>
 *   <li>保留原始資料 - 可用於重算統計或問題追蹤</li>
 * </ul>
 *
 * <p>文件 ID 由 MongoDB/Firestore 自動產生（ObjectId）。
 *
 * <p>{@code processed} 欄位標記此批次是否已完成分析結算。
 * 新建的批次預設為 {@code false}，完成聚合統計後更新為 {@code true}。
 *
 * <p>成本效益分析（假設 Firestore 定價）：
 * <pre>
 * 每事件一文件：10,000 events × $0.18/100K writes = $0.018/day
 * 批次儲存：    100 batches × $0.18/100K writes   = $0.00018/day
 * 節省：約 100 倍
 * </pre>
 *
 * @see <a href="https://cloud.google.com/firestore/pricing">Firestore Pricing</a>
 */
@Document(collection = "raw_event_batches")
public record RawEventBatch(
    @Id String id,
    List<UsageEventData> events,
    int eventCount,
    Instant createdAt,
    boolean processed
) {
    /**
     * 從事件列表建立新的批次文件。
     *
     * <p>ID 設為 null，由 MongoDB/Firestore 自動產生 ObjectId。
     *
     * @param events 要批次儲存的用量事件列表
     * @return 新建立的 RawEventBatch 實例
     * @throws IllegalArgumentException 如果事件列表為空
     */
    public static RawEventBatch create(List<UsageEventData> events) {
        if (events.isEmpty()) {
            throw new IllegalArgumentException("Events list cannot be empty");
        }

        return new RawEventBatch(null, events, events.size(), Instant.now(), false);
    }
}
