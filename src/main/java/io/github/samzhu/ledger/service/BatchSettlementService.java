package io.github.samzhu.ledger.service;

import java.util.List;

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
        log.info("Starting scheduled batch settlement...");
        int processed = processAllPendingBatches();
        log.info("Scheduled batch settlement completed: {} batches processed", processed);
    }

    /**
     * 手動觸發結算（供管理介面或測試使用）。
     *
     * @return 處理的批次數量
     */
    public int triggerSettlement() {
        log.info("Manual settlement triggered");
        int processed = processAllPendingBatches();
        log.info("Manual settlement completed: {} batches processed", processed);
        return processed;
    }

    /**
     * 處理所有未結算的批次。
     *
     * @return 成功處理的批次數量
     */
    private int processAllPendingBatches() {
        long startTime = System.currentTimeMillis();

        List<RawEventBatch> pendingBatches = rawEventBatchRepository.findByProcessedFalseOrderByCreatedAtAsc();

        if (pendingBatches.isEmpty()) {
            log.info("No pending batches to settle");
            return 0;
        }

        log.info("Found {} pending batches to settle", pendingBatches.size());

        int successCount = 0;
        int failCount = 0;
        int totalEvents = 0;

        for (RawEventBatch batch : pendingBatches) {
            try {
                List<UsageEvent> events = batch.events();
                totalEvents += events.size();

                aggregationService.processBatch(events);
                markAsProcessed(batch.id());

                successCount++;
                log.debug("Batch settled: id={}, events={}", batch.id(), events.size());
            } catch (Exception e) {
                failCount++;
                log.error("Failed to settle batch {}: {}", batch.id(), e.getMessage(), e);
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Settlement stats: {} success, {} failed, {} events in {}ms",
            successCount, failCount, totalEvents, duration);

        return successCount;
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
}
