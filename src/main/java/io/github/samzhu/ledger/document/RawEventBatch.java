package io.github.samzhu.ledger.document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import io.github.samzhu.ledger.dto.UsageEvent;

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
 * <p>文件 ID 格式：{@code {date}_{timestamp}_{uuid}}，
 * 例如 {@code 2025-12-10_1733817600000_abc12345}
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
    LocalDate date,
    List<UsageEvent> events,
    int eventCount,
    Instant createdAt
) {
    /**
     * 從事件列表建立新的批次文件。
     *
     * <p>自動產生唯一 ID 並記錄建立時間。
     *
     * @param events 要批次儲存的用量事件列表
     * @return 新建立的 RawEventBatch 實例
     * @throws IllegalArgumentException 如果事件列表為空
     */
    public static RawEventBatch create(List<UsageEvent> events) {
        if (events.isEmpty()) {
            throw new IllegalArgumentException("Events list cannot be empty");
        }

        LocalDate date = events.get(0).date();
        Instant now = Instant.now();
        String id = createId(date, now);

        return new RawEventBatch(id, date, events, events.size(), now);
    }

    /**
     * 產生唯一的批次 ID。
     *
     * <p>組合日期、時間戳和 UUID 確保唯一性，即使在高併發情況下也不會衝突。
     *
     * @param date 批次日期
     * @param timestamp 建立時間
     * @return 唯一 ID，格式為 {@code YYYY-MM-DD_epochMillis_uuid8}
     */
    public static String createId(LocalDate date, Instant timestamp) {
        return date.toString() + "_" + timestamp.toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
