package io.github.samzhu.ledger.service;

import java.util.List;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.github.samzhu.ledger.document.RawEventBatch;
import io.github.samzhu.ledger.dto.UsageEvent;
import io.github.samzhu.ledger.repository.RawEventBatchRepository;

/**
 * 批次結算服務，負責定時處理未結算的原始事件批次。
 *
 * <p>此服務在每小時整點執行，處理流程：
 * <ol>
 *   <li>查詢所有 {@code processed=false} 的 {@link RawEventBatch}</li>
 *   <li>呼叫 {@link UsageAggregationService} 執行聚合統計</li>
 *   <li>標記批次 {@code processed=true}</li>
 * </ol>
 *
 * <p>設計優點：
 * <ul>
 *   <li>寫入與聚合分離，提高寫入效能</li>
 *   <li>聚合失敗可自動重試（processed 仍為 false）</li>
 *   <li>支援批次重新處理（手動設回 processed=false）</li>
 * </ul>
 *
 * @see EventBufferService
 * @see UsageAggregationService
 */
@Service
public class BatchSettlementService {

    private static final Logger log = LoggerFactory.getLogger(BatchSettlementService.class);

    private final RawEventBatchRepository rawEventBatchRepository;
    private final UsageAggregationService aggregationService;
    private final MongoTemplate mongoTemplate;

    public BatchSettlementService(
            RawEventBatchRepository rawEventBatchRepository,
            UsageAggregationService aggregationService,
            MongoTemplate mongoTemplate) {
        this.rawEventBatchRepository = rawEventBatchRepository;
        this.aggregationService = aggregationService;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 定時結算任務，每小時整點執行。
     *
     * <p>查詢所有未結算的批次，依序執行聚合並標記完成。
     */
    @Scheduled(cron = "${ledger.batch.settlement-cron:0 0 * * * *}")
    public void settlePendingBatches() {
        log.info("Starting batch settlement...");
        long startTime = System.currentTimeMillis();

        List<RawEventBatch> pendingBatches = rawEventBatchRepository.findByProcessedFalseOrderByCreatedAtAsc();

        if (pendingBatches.isEmpty()) {
            log.info("No pending batches to settle");
            return;
        }

        log.info("Found {} pending batches to settle", pendingBatches.size());

        int successCount = 0;
        int failCount = 0;
        int totalEvents = 0;

        for (RawEventBatch batch : pendingBatches) {
            try {
                List<UsageEvent> events = batch.events();
                totalEvents += events.size();

                // 執行聚合統計
                aggregationService.processBatch(events);

                // 標記為已處理
                markAsProcessed(batch.id());

                successCount++;
                log.debug("Batch settled: id={}, events={}", batch.id(), events.size());
            } catch (Exception e) {
                failCount++;
                log.error("Failed to settle batch {}: {}", batch.id(), e.getMessage(), e);
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Batch settlement completed: {} batches ({} events) in {}ms, failed: {}",
            successCount, totalEvents, duration, failCount);
    }

    /**
     * 標記批次為已處理。
     *
     * @param batchId 批次 ID
     */
    private void markAsProcessed(String batchId) {
        Query query = Query.query(Criteria.where("_id").is(batchId));
        Update update = Update.update("processed", true);
        mongoTemplate.updateFirst(query, update, RawEventBatch.class);
    }

    /**
     * 手動觸發結算（供管理介面或測試使用）。
     *
     * @return 處理的批次數量
     */
    public int triggerSettlement() {
        log.info("Manual settlement triggered");

        // Debug: 先查詢所有文件數量
        long totalCount = rawEventBatchRepository.count();
        log.info("Manual settlement: total documents in raw_event_batches = {}", totalCount);

        // Debug: 用 MongoTemplate 直接查詢原始 Document，看欄位名稱和值
        Query allDocsQuery = new Query().limit(1);
        List<Document> rawDocs = mongoTemplate.find(allDocsQuery, Document.class, "raw_event_batches");
        if (!rawDocs.isEmpty()) {
            Document doc = rawDocs.get(0);
            log.info("Manual settlement: raw document fields = {}", doc.keySet());
            log.info("Manual settlement: processed field value = {}, type = {}",
                doc.get("processed"),
                doc.get("processed") != null ? doc.get("processed").getClass().getSimpleName() : "null");

            // 查看 events 欄位結構
            Object events = doc.get("events");
            if (events instanceof List<?> eventList && !eventList.isEmpty()) {
                Object firstEvent = eventList.get(0);
                if (firstEvent instanceof Document eventDoc) {
                    log.info("Manual settlement: first event fields = {}", eventDoc.keySet());
                    Object data = eventDoc.get("data");
                    if (data instanceof Document dataDoc) {
                        log.info("Manual settlement: event.data fields = {}", dataDoc.keySet());
                    }
                }
            }
        }

        // Debug: 用 MongoTemplate 直接查詢，看是否有 processed=false 的文件
        Query debugQuery = Query.query(Criteria.where("processed").is(false));
        long pendingCount = mongoTemplate.count(debugQuery, "raw_event_batches");
        log.info("Manual settlement: [MongoTemplate count] processed=false => {}", pendingCount);

        // === 比較三種查詢方式 ===

        // 方式 1: Derived Query (方法名稱推導)
        List<RawEventBatch> derivedResult;
        try {
            derivedResult = rawEventBatchRepository.findByProcessedFalseOrderByCreatedAtAsc();
            log.info("Manual settlement: [Derived Query] findByProcessedFalse => {} batches", derivedResult.size());
        } catch (Exception e) {
            log.error("Manual settlement: [Derived Query] failed: {}", e.getMessage(), e);
            derivedResult = List.of();
        }

        // 方式 2: @Query 註解 (明確 JSON 查詢)
        List<RawEventBatch> queryAnnotationResult;
        try {
            queryAnnotationResult = rawEventBatchRepository.findPendingBatchesWithQuery();
            log.info("Manual settlement: [@Query annotation] findPendingBatchesWithQuery => {} batches", queryAnnotationResult.size());
        } catch (Exception e) {
            log.error("Manual settlement: [@Query annotation] failed: {}", e.getMessage(), e);
            queryAnnotationResult = List.of();
        }

        // 方式 3: @Query 查詢所有 (驗證反序列化)
        List<RawEventBatch> allBatchesResult;
        try {
            allBatchesResult = rawEventBatchRepository.findAllBatchesWithQuery();
            log.info("Manual settlement: [@Query findAll] findAllBatchesWithQuery => {} batches", allBatchesResult.size());
            if (!allBatchesResult.isEmpty()) {
                RawEventBatch firstBatch = allBatchesResult.get(0);
                log.info("Manual settlement: [@Query findAll] first batch: id={}, processed={}, eventCount={}",
                    firstBatch.id(), firstBatch.processed(), firstBatch.eventCount());
            }
        } catch (Exception e) {
            log.error("Manual settlement: [@Query findAll] failed: {}", e.getMessage(), e);
            allBatchesResult = List.of();
        }

        // 使用 @Query 結果（優先使用明確定義的查詢）
        List<RawEventBatch> pendingBatches = !queryAnnotationResult.isEmpty() ? queryAnnotationResult : derivedResult;
        log.info("Manual settlement: using {} batches for processing", pendingBatches.size());

        if (pendingBatches.isEmpty()) {
            log.info("Manual settlement: no pending batches found (total docs: {})", totalCount);
            return 0;
        }

        log.info("Manual settlement: found {} pending batches", pendingBatches.size());

        int count = 0;
        for (RawEventBatch batch : pendingBatches) {
            try {
                log.info("Manual settlement: processing batch id={}, eventCount={}", batch.id(), batch.eventCount());
                List<UsageEvent> events = batch.events();
                log.info("Manual settlement: batch {} has {} events to process", batch.id(), events.size());

                if (!events.isEmpty()) {
                    UsageEvent firstEvent = events.get(0);
                    log.info("Manual settlement: first event - eventId={}, userId={}, model={}, inputTokens={}",
                        firstEvent.eventId(), firstEvent.userId(), firstEvent.model(), firstEvent.inputTokens());
                }

                aggregationService.processBatch(events);
                markAsProcessed(batch.id());
                count++;
                log.info("Manual settlement: batch {} processed successfully", batch.id());
            } catch (Exception e) {
                log.error("Manual settlement failed for batch {}: {}", batch.id(), e.getMessage(), e);
            }
        }

        log.info("Manual settlement completed: {} batches processed", count);
        return count;
    }
}
