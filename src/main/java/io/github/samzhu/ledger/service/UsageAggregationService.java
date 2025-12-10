package io.github.samzhu.ledger.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import io.github.samzhu.ledger.document.DailyModelUsage;
import io.github.samzhu.ledger.document.DailyUserUsage;
import io.github.samzhu.ledger.document.SystemStats;
import io.github.samzhu.ledger.document.UserSummary;
import io.github.samzhu.ledger.dto.UsageEvent;

/**
 * 用量事件聚合服務。
 *
 * <p>將原始用量事件聚合更新到多個維度的統計文件：
 * <ul>
 *   <li>{@code daily_user_usage} - 用戶日用量</li>
 *   <li>{@code daily_model_usage} - 模型日用量</li>
 *   <li>{@code user_summary} - 用戶累計統計</li>
 *   <li>{@code system_stats} - 系統日統計</li>
 * </ul>
 *
 * <p>使用 MongoDB bulk operations 搭配原子 {@code $inc} 操作，確保：
 * <ul>
 *   <li>高效能 - 批次操作減少網路往返</li>
 *   <li>原子性 - 每筆更新獨立原子，避免 race condition</li>
 *   <li>冪等性 - upsert 模式，文件不存在時自動建立</li>
 * </ul>
 *
 * @see <a href="https://docs.spring.io/spring-data/mongodb/reference/mongodb/template-update.html">Spring Data MongoDB Update Operations</a>
 */
@Service
public class UsageAggregationService {

    private static final Logger log = LoggerFactory.getLogger(UsageAggregationService.class);

    private final MongoTemplate mongoTemplate;
    private final CostCalculationService costService;

    public UsageAggregationService(MongoTemplate mongoTemplate, CostCalculationService costService) {
        this.mongoTemplate = mongoTemplate;
        this.costService = costService;
        log.info("UsageAggregationService initialized");
    }

    /**
     * 批次處理用量事件並更新所有聚合文件。
     *
     * <p>處理流程：
     * <ol>
     *   <li>依 (date, userId) 分組 → 更新 daily_user_usage</li>
     *   <li>依 (date, model) 分組 → 更新 daily_model_usage</li>
     *   <li>依 userId 分組 → 更新 user_summary</li>
     *   <li>依 date 分組 → 更新 system_stats</li>
     * </ol>
     *
     * @param events 要處理的用量事件列表
     */
    public void processBatch(List<UsageEvent> events) {
        if (events.isEmpty()) {
            log.debug("Empty batch, skipping aggregation");
            return;
        }

        long startTime = System.currentTimeMillis();
        log.info("Processing aggregation batch: {} events", events.size());

        updateDailyUserUsage(events);
        updateDailyModelUsage(events);
        updateUserSummary(events);
        updateSystemStats(events);

        long duration = System.currentTimeMillis() - startTime;
        log.info("Aggregation completed: {} events processed in {}ms", events.size(), duration);
    }

    /**
     * 更新用戶日用量聚合。
     */
    private void updateDailyUserUsage(List<UsageEvent> events) {
        BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, DailyUserUsage.class);

        Map<String, List<UsageEvent>> grouped = events.stream()
            .collect(Collectors.groupingBy(e -> DailyUserUsage.createId(e.date(), e.userId())));

        grouped.forEach((docId, userEvents) -> {
            UsageEvent first = userEvents.get(0);

            long totalInput = userEvents.stream().mapToLong(UsageEvent::inputTokens).sum();
            long totalOutput = userEvents.stream().mapToLong(UsageEvent::outputTokens).sum();
            long totalCacheCreation = userEvents.stream().mapToLong(UsageEvent::cacheCreationTokens).sum();
            long totalCacheRead = userEvents.stream().mapToLong(UsageEvent::cacheReadTokens).sum();
            long totalTokens = userEvents.stream().mapToLong(UsageEvent::totalTokens).sum();
            long totalLatency = userEvents.stream().mapToLong(UsageEvent::latencyMs).sum();
            int successCount = (int) userEvents.stream().filter(UsageEvent::isSuccess).count();
            int errorCount = userEvents.size() - successCount;

            BigDecimal totalCost = userEvents.stream()
                .map(costService::calculateCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            Query query = Query.query(Criteria.where("_id").is(docId));
            Update update = new Update()
                .setOnInsert("date", first.date())
                .setOnInsert("userId", first.userId())
                .inc("totalInputTokens", totalInput)
                .inc("totalOutputTokens", totalOutput)
                .inc("totalCacheCreationTokens", totalCacheCreation)
                .inc("totalCacheReadTokens", totalCacheRead)
                .inc("totalTokens", totalTokens)
                .inc("totalLatencyMs", totalLatency)
                .inc("requestCount", userEvents.size())
                .inc("successCount", successCount)
                .inc("errorCount", errorCount)
                .inc("estimatedCostUsd", totalCost.doubleValue())
                .set("lastUpdatedAt", Instant.now());

            bulkOps.upsert(query, update);
        });

        bulkOps.execute();
        log.debug("Updated daily_user_usage: {} documents", grouped.size());
    }

    /**
     * 更新模型日用量聚合。
     */
    private void updateDailyModelUsage(List<UsageEvent> events) {
        BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, DailyModelUsage.class);

        Map<String, List<UsageEvent>> grouped = events.stream()
            .collect(Collectors.groupingBy(e -> DailyModelUsage.createId(e.date(), e.model())));

        grouped.forEach((docId, modelEvents) -> {
            UsageEvent first = modelEvents.get(0);

            long totalInput = modelEvents.stream().mapToLong(UsageEvent::inputTokens).sum();
            long totalOutput = modelEvents.stream().mapToLong(UsageEvent::outputTokens).sum();
            long totalCacheCreation = modelEvents.stream().mapToLong(UsageEvent::cacheCreationTokens).sum();
            long totalCacheRead = modelEvents.stream().mapToLong(UsageEvent::cacheReadTokens).sum();
            long totalTokens = modelEvents.stream().mapToLong(UsageEvent::totalTokens).sum();
            int successCount = (int) modelEvents.stream().filter(UsageEvent::isSuccess).count();
            int errorCount = modelEvents.size() - successCount;

            BigDecimal totalCost = modelEvents.stream()
                .map(costService::calculateCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            Query query = Query.query(Criteria.where("_id").is(docId));
            Update update = new Update()
                .setOnInsert("date", first.date())
                .setOnInsert("model", first.model())
                .inc("totalInputTokens", totalInput)
                .inc("totalOutputTokens", totalOutput)
                .inc("totalCacheCreationTokens", totalCacheCreation)
                .inc("totalCacheReadTokens", totalCacheRead)
                .inc("totalTokens", totalTokens)
                .inc("requestCount", modelEvents.size())
                .inc("successCount", successCount)
                .inc("errorCount", errorCount)
                .inc("estimatedCostUsd", totalCost.doubleValue())
                .set("lastUpdatedAt", Instant.now());

            bulkOps.upsert(query, update);
        });

        bulkOps.execute();
        log.debug("Updated daily_model_usage: {} documents", grouped.size());
    }

    /**
     * 更新用戶累計統計。
     */
    private void updateUserSummary(List<UsageEvent> events) {
        BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, UserSummary.class);

        Map<String, List<UsageEvent>> grouped = events.stream()
            .collect(Collectors.groupingBy(UsageEvent::userId));

        grouped.forEach((userId, userEvents) -> {
            long totalInput = userEvents.stream().mapToLong(UsageEvent::inputTokens).sum();
            long totalOutput = userEvents.stream().mapToLong(UsageEvent::outputTokens).sum();
            long totalTokens = userEvents.stream().mapToLong(UsageEvent::totalTokens).sum();

            BigDecimal totalCost = userEvents.stream()
                .map(costService::calculateCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            Query query = Query.query(Criteria.where("_id").is(userId));
            Update update = new Update()
                .setOnInsert("userId", userId)
                .setOnInsert("firstSeenAt", Instant.now())
                .inc("totalInputTokens", totalInput)
                .inc("totalOutputTokens", totalOutput)
                .inc("totalTokens", totalTokens)
                .inc("totalRequestCount", userEvents.size())
                .inc("totalEstimatedCostUsd", totalCost.doubleValue())
                .set("lastActiveAt", Instant.now())
                .set("lastUpdatedAt", Instant.now());

            bulkOps.upsert(query, update);
        });

        bulkOps.execute();
        log.debug("Updated user_summary: {} documents", grouped.size());
    }

    /**
     * 更新系統日統計。
     */
    private void updateSystemStats(List<UsageEvent> events) {
        BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, SystemStats.class);

        Map<LocalDate, List<UsageEvent>> grouped = events.stream()
            .collect(Collectors.groupingBy(UsageEvent::date));

        grouped.forEach((date, dateEvents) -> {
            long totalInput = dateEvents.stream().mapToLong(UsageEvent::inputTokens).sum();
            long totalOutput = dateEvents.stream().mapToLong(UsageEvent::outputTokens).sum();
            long totalTokens = dateEvents.stream().mapToLong(UsageEvent::totalTokens).sum();

            BigDecimal totalCost = dateEvents.stream()
                .map(costService::calculateCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            Query query = Query.query(Criteria.where("_id").is(date.toString()));
            Update update = new Update()
                .setOnInsert("date", date)
                .inc("totalInputTokens", totalInput)
                .inc("totalOutputTokens", totalOutput)
                .inc("totalTokens", totalTokens)
                .inc("totalRequestCount", dateEvents.size())
                .inc("totalEstimatedCostUsd", totalCost.doubleValue())
                .set("lastUpdatedAt", Instant.now());

            bulkOps.upsert(query, update);
        });

        bulkOps.execute();
        log.debug("Updated system_stats: {} documents", grouped.size());
    }
}
