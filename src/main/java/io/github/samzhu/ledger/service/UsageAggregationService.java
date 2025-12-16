package io.github.samzhu.ledger.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.tdunning.math.stats.MergingDigest;
import com.tdunning.math.stats.TDigest;

import io.github.samzhu.ledger.config.LedgerProperties;
import io.github.samzhu.ledger.document.DailyModelUsage;
import io.github.samzhu.ledger.document.DailyUserUsage;
import io.github.samzhu.ledger.document.DailyUserUsage.CostBreakdown;
import io.github.samzhu.ledger.document.DailyUserUsage.HourlyBreakdown;
import io.github.samzhu.ledger.document.DailyUserUsage.ModelBreakdown;
import io.github.samzhu.ledger.document.SystemStats;
import io.github.samzhu.ledger.document.SystemStats.TopItem;
import io.github.samzhu.ledger.document.UserQuota;
import io.github.samzhu.ledger.dto.UsageEvent;

/**
 * 用量事件聚合服務（增強版）。
 *
 * <p>將原始用量事件聚合更新到多個維度的統計文件：
 * <ul>
 *   <li>{@code daily_user_usage} - 用戶日用量（含延遲百分位、Cache 效率、錯誤分布）</li>
 *   <li>{@code daily_model_usage} - 模型日用量（含延遲百分位、Cache 效率、錯誤分布）</li>
 *   <li>{@code user_quota} - 用戶配額與累計統計</li>
 *   <li>{@code system_stats} - 系統日統計（含成功率、延遲、排行榜）</li>
 * </ul>
 *
 * <p>新增功能：
 * <ul>
 *   <li>T-Digest 演算法計算延遲百分位 (P50/P90/P95/P99)</li>
 *   <li>錯誤類型分類統計</li>
 *   <li>Cache hit rate 和節省成本追蹤</li>
 *   <li>每小時請求分布</li>
 *   <li>成本細分（輸入/輸出/Cache 讀寫）</li>
 * </ul>
 *
 * @see <a href="https://docs.spring.io/spring-data/mongodb/reference/mongodb/template-update.html">Spring Data MongoDB Update Operations</a>
 */
@Service
public class UsageAggregationService {

    private static final Logger log = LoggerFactory.getLogger(UsageAggregationService.class);

    private final MongoTemplate mongoTemplate;
    private final CostCalculationService costService;
    private final LatencyDigestService digestService;
    private final LedgerProperties properties;

    public UsageAggregationService(
            MongoTemplate mongoTemplate,
            CostCalculationService costService,
            LatencyDigestService digestService,
            LedgerProperties properties) {
        this.mongoTemplate = mongoTemplate;
        this.costService = costService;
        this.digestService = digestService;
        this.properties = properties;
        log.info("UsageAggregationService initialized with enhanced analytics");
    }

    /**
     * 批次處理用量事件並更新所有聚合文件。
     *
     * <p>處理流程：
     * <ol>
     *   <li>依 (date, userId) 分組 → 更新 daily_user_usage</li>
     *   <li>依 (date, model) 分組 → 更新 daily_model_usage</li>
     *   <li>依 userId 分組 → 更新 user_quota</li>
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
        updateUserQuota(events);
        updateSystemStats(events);

        long duration = System.currentTimeMillis() - startTime;
        log.info("Aggregation completed: {} events processed in {}ms", events.size(), duration);
    }

    /**
     * 更新用戶日用量聚合（增強版）。
     *
     * <p>新增：延遲百分位、錯誤分布、Cache 效率、每小時分布、成本細分。
     */
    private void updateDailyUserUsage(List<UsageEvent> events) {
        BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, DailyUserUsage.class);

        Map<String, List<UsageEvent>> grouped = events.stream()
            .collect(Collectors.groupingBy(e -> DailyUserUsage.createId(e.date(), e.userId())));

        grouped.forEach((docId, userEvents) -> {
            UsageEvent first = userEvents.get(0);

            // === 基本統計 ===
            // totalInputTokens = inputTokens + cacheCreationTokens + cacheReadTokens (由 UsageEvent 計算)
            long totalInput = userEvents.stream().mapToLong(UsageEvent::totalInputTokens).sum();
            long totalOutput = userEvents.stream().mapToLong(UsageEvent::outputTokens).sum();
            long totalCacheCreation = userEvents.stream().mapToLong(UsageEvent::cacheCreationTokens).sum();
            long totalCacheRead = userEvents.stream().mapToLong(UsageEvent::cacheReadTokens).sum();
            long totalTokens = userEvents.stream().mapToLong(UsageEvent::totalTokens).sum();
            int successCount = (int) userEvents.stream().filter(UsageEvent::isSuccess).count();
            int errorCount = userEvents.size() - successCount;

            BigDecimal totalCost = costService.calculateBatchCost(userEvents);

            // === 錯誤類型分布 ===
            Map<String, Integer> errorBreakdown = aggregateErrorBreakdown(userEvents);

            // === T-Digest 延遲百分位 ===
            TDigest digest = loadOrCreateDigest(docId, DailyUserUsage.class);
            userEvents.forEach(e -> digest.add(e.latencyMs()));
            DailyUserUsage.LatencyStats latencyStats = calculateLatencyStats(digest);
            byte[] digestBytes = serializeDigest(digest);

            // === Cache 效率 ===
            DailyUserUsage.CacheEfficiency cacheEfficiency = calculateCacheEfficiency(userEvents);

            // === 每小時分布 ===
            Map<Integer, HourlyBreakdown> hourlyBreakdown = aggregateHourlyBreakdown(userEvents);
            int peakHour = findPeakHour(hourlyBreakdown);
            int peakHourRequests = hourlyBreakdown.containsKey(peakHour)
                ? hourlyBreakdown.get(peakHour).requestCount() : 0;

            // === 模型分布 ===
            Map<String, ModelBreakdown> modelBreakdown = aggregateModelBreakdown(userEvents);

            // === 成本細分 ===
            CostBreakdown costBreakdown = costService.calculateCostBreakdown(userEvents);

            Query query = Query.query(Criteria.where("_id").is(docId));
            Update update = new Update()
                .setOnInsert("date", first.date())
                .setOnInsert("userId", first.userId())
                .inc("totalInputTokens", totalInput)
                .inc("totalOutputTokens", totalOutput)
                .inc("totalCacheCreationTokens", totalCacheCreation)
                .inc("totalCacheReadTokens", totalCacheRead)
                .inc("totalTokens", totalTokens)
                .inc("requestCount", userEvents.size())
                .inc("successCount", successCount)
                .inc("errorCount", errorCount)
                .inc("estimatedCostUsd", totalCost.doubleValue())
                .set("latencyStats", latencyStats)
                .set("latencyDigest", digestBytes)
                .set("cacheEfficiency", cacheEfficiency)
                .set("peakHour", peakHour)
                .set("peakHourRequests", peakHourRequests)
                .set("costBreakdown", costBreakdown)
                .set("lastUpdatedAt", Instant.now());

            // 錯誤分布使用 $inc
            errorBreakdown.forEach((type, count) ->
                update.inc("errorBreakdown." + type, count));

            // 每小時分布使用 $inc
            hourlyBreakdown.forEach((hour, breakdown) -> {
                update.inc("hourlyBreakdown." + hour + ".requestCount", breakdown.requestCount());
                update.inc("hourlyBreakdown." + hour + ".totalTokens", breakdown.totalTokens());
                update.inc("hourlyBreakdown." + hour + ".costUsd", breakdown.costUsd().doubleValue());
            });

            // 模型分布使用 $inc
            modelBreakdown.forEach((model, breakdown) -> {
                String prefix = "modelBreakdown." + sanitizeFieldName(model) + ".";
                update.inc(prefix + "inputTokens", breakdown.inputTokens());
                update.inc(prefix + "outputTokens", breakdown.outputTokens());
                update.inc(prefix + "cacheReadTokens", breakdown.cacheReadTokens());
                update.inc(prefix + "requestCount", breakdown.requestCount());
                update.inc(prefix + "successCount", breakdown.successCount());
                update.inc(prefix + "errorCount", breakdown.errorCount());
                update.inc(prefix + "costUsd", breakdown.costUsd().doubleValue());
            });

            bulkOps.upsert(query, update);
        });

        bulkOps.execute();
        log.debug("Updated daily_user_usage: {} documents", grouped.size());
    }

    /**
     * 更新模型日用量聚合（增強版）。
     */
    private void updateDailyModelUsage(List<UsageEvent> events) {
        BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, DailyModelUsage.class);

        Map<String, List<UsageEvent>> grouped = events.stream()
            .collect(Collectors.groupingBy(e -> DailyModelUsage.createId(e.date(), e.model())));

        grouped.forEach((docId, modelEvents) -> {
            UsageEvent first = modelEvents.get(0);

            // === 基本統計 ===
            // totalInputTokens = inputTokens + cacheCreationTokens + cacheReadTokens (由 UsageEvent 計算)
            long totalInput = modelEvents.stream().mapToLong(UsageEvent::totalInputTokens).sum();
            long totalOutput = modelEvents.stream().mapToLong(UsageEvent::outputTokens).sum();
            long totalCacheCreation = modelEvents.stream().mapToLong(UsageEvent::cacheCreationTokens).sum();
            long totalCacheRead = modelEvents.stream().mapToLong(UsageEvent::cacheReadTokens).sum();
            long totalTokens = modelEvents.stream().mapToLong(UsageEvent::totalTokens).sum();
            int successCount = (int) modelEvents.stream().filter(UsageEvent::isSuccess).count();
            int errorCount = modelEvents.size() - successCount;
            int uniqueUsers = (int) modelEvents.stream().map(UsageEvent::userId).distinct().count();

            BigDecimal totalCost = costService.calculateBatchCost(modelEvents);

            // === 錯誤類型分布 ===
            Map<String, Integer> errorBreakdown = aggregateErrorBreakdown(modelEvents);

            // === T-Digest 延遲百分位 ===
            TDigest digest = loadOrCreateDigest(docId, DailyModelUsage.class);
            modelEvents.forEach(e -> digest.add(e.latencyMs()));
            DailyModelUsage.LatencyStats latencyStats = calculateModelLatencyStats(digest);
            byte[] digestBytes = serializeDigest(digest);

            // === Cache 效率 ===
            DailyModelUsage.CacheEfficiency cacheEfficiency = calculateModelCacheEfficiency(modelEvents);

            // === 每小時分布 ===
            Map<Integer, Integer> hourlyRequestCount = aggregateHourlyRequestCount(modelEvents);
            int peakHour = findPeakHourFromCount(hourlyRequestCount);
            int peakHourRequests = hourlyRequestCount.getOrDefault(peakHour, 0);

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
                .set("uniqueUsers", uniqueUsers)
                .inc("estimatedCostUsd", totalCost.doubleValue())
                .set("latencyStats", latencyStats)
                .set("latencyDigest", digestBytes)
                .set("cacheEfficiency", cacheEfficiency)
                .set("peakHour", peakHour)
                .set("peakHourRequests", peakHourRequests)
                .set("lastUpdatedAt", Instant.now());

            // 錯誤分布使用 $inc
            errorBreakdown.forEach((type, count) ->
                update.inc("errorBreakdown." + type, count));

            // 每小時分布使用 $inc
            hourlyRequestCount.forEach((hour, count) ->
                update.inc("hourlyRequestCount." + hour, count));

            bulkOps.upsert(query, update);
        });

        bulkOps.execute();
        log.debug("Updated daily_model_usage: {} documents", grouped.size());
    }

    /**
     * 更新用戶配額與累計統計（取代 UserSummary）。
     */
    private void updateUserQuota(List<UsageEvent> events) {
        BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, UserQuota.class);

        Map<String, List<UsageEvent>> grouped = events.stream()
            .collect(Collectors.groupingBy(UsageEvent::userId));

        grouped.forEach((userId, userEvents) -> {
            // totalInputTokens = inputTokens + cacheCreationTokens + cacheReadTokens (由 UsageEvent 計算)
            long totalInput = userEvents.stream().mapToLong(UsageEvent::totalInputTokens).sum();
            long totalOutput = userEvents.stream().mapToLong(UsageEvent::outputTokens).sum();
            long totalTokens = userEvents.stream().mapToLong(UsageEvent::totalTokens).sum();

            BigDecimal totalCost = costService.calculateBatchCost(userEvents);

            Query query = Query.query(Criteria.where("_id").is(userId));
            Update update = new Update()
                .setOnInsert("userId", userId)
                .setOnInsert("firstSeenAt", Instant.now())
                .setOnInsert("quotaConfig", UserQuota.QuotaConfig.defaults())
                .setOnInsert("periodStartDate", LocalDate.now())
                .setOnInsert("periodEndDate", LocalDate.now().plusMonths(1))
                .setOnInsert("periodTokenUsed", 0L)
                .setOnInsert("periodCostUsed", BigDecimal.ZERO)
                .setOnInsert("periodRequestCount", 0)
                .setOnInsert("tokenUsagePercent", 0.0)
                .setOnInsert("costUsagePercent", 0.0)
                .setOnInsert("quotaExceeded", false)
                .inc("totalInputTokens", totalInput)
                .inc("totalOutputTokens", totalOutput)
                .inc("totalTokens", totalTokens)
                .inc("totalRequestCount", userEvents.size())
                .inc("totalEstimatedCostUsd", totalCost.doubleValue())
                .inc("periodTokenUsed", totalTokens)
                .inc("periodCostUsed", totalCost.doubleValue())
                .inc("periodRequestCount", userEvents.size())
                .set("lastActiveAt", Instant.now())
                .set("lastUpdatedAt", Instant.now());

            bulkOps.upsert(query, update);
        });

        bulkOps.execute();
        log.debug("Updated user_quota: {} documents", grouped.size());
    }

    /**
     * 更新系統日統計（增強版）。
     */
    private void updateSystemStats(List<UsageEvent> events) {
        BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, SystemStats.class);

        Map<LocalDate, List<UsageEvent>> grouped = events.stream()
            .collect(Collectors.groupingBy(UsageEvent::date));

        grouped.forEach((date, dateEvents) -> {
            // totalInputTokens = inputTokens + cacheCreationTokens + cacheReadTokens (由 UsageEvent 計算)
            long totalInput = dateEvents.stream().mapToLong(UsageEvent::totalInputTokens).sum();
            long totalOutput = dateEvents.stream().mapToLong(UsageEvent::outputTokens).sum();
            long totalTokens = dateEvents.stream().mapToLong(UsageEvent::totalTokens).sum();
            int successCount = (int) dateEvents.stream().filter(UsageEvent::isSuccess).count();
            int errorCount = dateEvents.size() - successCount;
            int uniqueUsers = (int) dateEvents.stream().map(UsageEvent::userId).distinct().count();

            BigDecimal totalCost = costService.calculateBatchCost(dateEvents);

            // 成功率
            double successRate = dateEvents.isEmpty() ? 0.0 :
                (double) successCount / dateEvents.size();

            // 延遲統計
            TDigest digest = loadOrCreateDigest(date.toString(), SystemStats.class);
            dateEvents.forEach(e -> digest.add(e.latencyMs()));
            double avgLatency = dateEvents.isEmpty() ? 0.0 :
                dateEvents.stream().mapToLong(UsageEvent::latencyMs).average().orElse(0.0);
            double p50 = digest.size() > 0 ? digest.quantile(0.5) : 0.0;
            double p90 = digest.size() > 0 ? digest.quantile(0.9) : 0.0;
            double p99 = digest.size() > 0 ? digest.quantile(0.99) : 0.0;

            // Cache 效率
            long totalCacheRead = dateEvents.stream().mapToLong(UsageEvent::cacheReadTokens).sum();
            double cacheHitRate = totalInput > 0 ? (double) totalCacheRead / totalInput : 0.0;
            BigDecimal cacheSaved = costService.calculateCacheSavings(dateEvents);

            // 每小時分布
            Map<Integer, Integer> hourlyRequestCount = aggregateHourlyRequestCount(dateEvents);
            int peakHour = findPeakHourFromCount(hourlyRequestCount);
            int peakHourRequests = hourlyRequestCount.getOrDefault(peakHour, 0);

            // 排行榜
            List<TopItem> topModels = calculateTopModels(dateEvents, 5);
            List<TopItem> topUsers = calculateTopUsers(dateEvents, 10);

            Query query = Query.query(Criteria.where("_id").is(date.toString()));
            Update update = new Update()
                .setOnInsert("date", date)
                .inc("totalInputTokens", totalInput)
                .inc("totalOutputTokens", totalOutput)
                .inc("totalTokens", totalTokens)
                .inc("totalRequestCount", dateEvents.size())
                .set("uniqueUsers", uniqueUsers)
                .inc("totalEstimatedCostUsd", totalCost.doubleValue())
                .inc("successCount", successCount)
                .inc("errorCount", errorCount)
                .set("successRate", successRate)
                .set("avgLatencyMs", avgLatency)
                .set("p50LatencyMs", p50)
                .set("p90LatencyMs", p90)
                .set("p99LatencyMs", p99)
                .set("systemCacheHitRate", cacheHitRate)
                .inc("systemCacheSavedUsd", cacheSaved.doubleValue())
                .set("peakHour", peakHour)
                .set("peakHourRequests", peakHourRequests)
                .set("topModels", topModels)
                .set("topUsers", topUsers)
                .set("lastUpdatedAt", Instant.now());

            // 每小時分布使用 $inc
            hourlyRequestCount.forEach((hour, count) ->
                update.inc("hourlyRequestCount." + hour, count));

            bulkOps.upsert(query, update);
        });

        bulkOps.execute();
        log.debug("Updated system_stats: {} documents", grouped.size());
    }

    // ========== 輔助方法 ==========

    /**
     * 聚合錯誤類型分布。
     */
    private Map<String, Integer> aggregateErrorBreakdown(List<UsageEvent> events) {
        return events.stream()
            .filter(e -> !e.isSuccess())
            .collect(Collectors.groupingBy(
                e -> normalizeErrorType(e.errorType()),
                Collectors.summingInt(e -> 1)));
    }

    /**
     * 正規化錯誤類型。
     */
    private String normalizeErrorType(String errorType) {
        if (errorType == null || errorType.isBlank()) {
            return "unknown";
        }
        return switch (errorType.toLowerCase()) {
            case "rate_limit_error", "rate_limited" -> "rate_limit";
            case "overloaded_error", "overloaded" -> "overloaded";
            case "invalid_request_error" -> "invalid_request";
            case "authentication_error" -> "authentication";
            case "context_length_exceeded" -> "context_length";
            case "server_error", "internal_error" -> "server_error";
            default -> "unknown";
        };
    }

    /**
     * 聚合每小時用量細分（用於 DailyUserUsage）。
     */
    private Map<Integer, HourlyBreakdown> aggregateHourlyBreakdown(List<UsageEvent> events) {
        Map<Integer, HourlyBreakdown> result = new HashMap<>();

        Map<Integer, List<UsageEvent>> byHour = events.stream()
            .collect(Collectors.groupingBy(
                e -> Integer.valueOf(e.timestamp().atZone(ZoneOffset.UTC).getHour())));

        byHour.forEach((hour, hourEvents) -> {
            int requestCount = hourEvents.size();
            long totalTokens = hourEvents.stream().mapToLong(UsageEvent::totalTokens).sum();
            BigDecimal costUsd = costService.calculateBatchCost(hourEvents);
            result.put(hour, new HourlyBreakdown(requestCount, totalTokens, costUsd));
        });

        return result;
    }

    /**
     * 聚合每小時請求數（用於 DailyModelUsage 和 SystemStats）。
     */
    private Map<Integer, Integer> aggregateHourlyRequestCount(List<UsageEvent> events) {
        return events.stream()
            .collect(Collectors.groupingBy(
                e -> Integer.valueOf(e.timestamp().atZone(ZoneOffset.UTC).getHour()),
                Collectors.summingInt(e -> 1)));
    }

    /**
     * 聚合模型維度細分。
     */
    private Map<String, ModelBreakdown> aggregateModelBreakdown(List<UsageEvent> events) {
        Map<String, ModelBreakdown> result = new HashMap<>();

        Map<String, List<UsageEvent>> byModel = events.stream()
            .collect(Collectors.groupingBy(UsageEvent::model));

        byModel.forEach((model, modelEvents) -> {
            // totalInputTokens = inputTokens + cacheCreationTokens + cacheReadTokens (由 UsageEvent 計算)
            long inputTokens = modelEvents.stream().mapToLong(UsageEvent::totalInputTokens).sum();
            long outputTokens = modelEvents.stream().mapToLong(UsageEvent::outputTokens).sum();
            long cacheReadTokens = modelEvents.stream().mapToLong(UsageEvent::cacheReadTokens).sum();
            int requestCount = modelEvents.size();
            int successCount = (int) modelEvents.stream().filter(UsageEvent::isSuccess).count();
            int errorCount = requestCount - successCount;
            BigDecimal costUsd = costService.calculateBatchCost(modelEvents);

            result.put(model, new ModelBreakdown(
                inputTokens, outputTokens, cacheReadTokens,
                requestCount, successCount, errorCount, costUsd));
        });

        return result;
    }

    /**
     * 計算 Cache 效率指標（用於 DailyUserUsage）。
     *
     * <p>hitRate = cacheReadTokens / totalInputTokens
     */
    private DailyUserUsage.CacheEfficiency calculateCacheEfficiency(List<UsageEvent> events) {
        // totalInputTokens = inputTokens + cacheCreationTokens + cacheReadTokens
        long totalInput = events.stream().mapToLong(UsageEvent::totalInputTokens).sum();
        long cacheRead = events.stream().mapToLong(UsageEvent::cacheReadTokens).sum();

        if (totalInput == 0) {
            return DailyUserUsage.CacheEfficiency.empty();
        }

        double hitRate = (double) cacheRead / totalInput;
        BigDecimal savedCost = costService.calculateCacheSavings(events);

        return new DailyUserUsage.CacheEfficiency(hitRate, cacheRead, savedCost);
    }

    /**
     * 計算 Cache 效率指標（用於 DailyModelUsage）。
     *
     * <p>hitRate = cacheReadTokens / totalInputTokens
     */
    private DailyModelUsage.CacheEfficiency calculateModelCacheEfficiency(List<UsageEvent> events) {
        // totalInputTokens = inputTokens + cacheCreationTokens + cacheReadTokens
        long totalInput = events.stream().mapToLong(UsageEvent::totalInputTokens).sum();
        long cacheRead = events.stream().mapToLong(UsageEvent::cacheReadTokens).sum();

        if (totalInput == 0) {
            return DailyModelUsage.CacheEfficiency.empty();
        }

        double hitRate = (double) cacheRead / totalInput;
        BigDecimal savedCost = costService.calculateCacheSavings(events);

        return new DailyModelUsage.CacheEfficiency(hitRate, cacheRead, savedCost);
    }

    /**
     * 找出尖峰小時（從 HourlyBreakdown）。
     */
    private int findPeakHour(Map<Integer, HourlyBreakdown> hourlyBreakdown) {
        return hourlyBreakdown.entrySet().stream()
            .max(Comparator.comparingInt(e -> e.getValue().requestCount()))
            .map(Map.Entry::getKey)
            .orElse(0);
    }

    /**
     * 找出尖峰小時（從請求數 Map）。
     */
    private int findPeakHourFromCount(Map<Integer, Integer> hourlyRequestCount) {
        return hourlyRequestCount.entrySet().stream()
            .max(Comparator.comparingInt(Map.Entry::getValue))
            .map(Map.Entry::getKey)
            .orElse(0);
    }

    /**
     * 計算熱門模型排行榜。
     */
    private List<TopItem> calculateTopModels(List<UsageEvent> events, int limit) {
        Map<String, List<UsageEvent>> byModel = events.stream()
            .collect(Collectors.groupingBy(UsageEvent::model));

        return byModel.entrySet().stream()
            .map(e -> new TopItem(
                e.getKey(),
                e.getValue().size(),
                e.getValue().stream().mapToLong(UsageEvent::totalTokens).sum(),
                costService.calculateBatchCost(e.getValue())))
            .sorted(Comparator.comparingInt(TopItem::requestCount).reversed())
            .limit(limit)
            .toList();
    }

    /**
     * 計算活躍用戶排行榜。
     */
    private List<TopItem> calculateTopUsers(List<UsageEvent> events, int limit) {
        Map<String, List<UsageEvent>> byUser = events.stream()
            .collect(Collectors.groupingBy(UsageEvent::userId));

        return byUser.entrySet().stream()
            .map(e -> new TopItem(
                e.getKey(),
                e.getValue().size(),
                e.getValue().stream().mapToLong(UsageEvent::totalTokens).sum(),
                costService.calculateBatchCost(e.getValue())))
            .sorted(Comparator.comparingLong(TopItem::totalTokens).reversed())
            .limit(limit)
            .toList();
    }

    /**
     * 載入或建立 T-Digest。
     */
    private TDigest loadOrCreateDigest(String docId, Class<?> documentClass) {
        // 嘗試從現有文件載入 digest bytes
        Query query = Query.query(Criteria.where("_id").is(docId));
        query.fields().include("latencyDigest");

        var doc = mongoTemplate.findOne(query, documentClass);
        if (doc != null) {
            byte[] bytes = extractDigestBytes(doc);
            if (bytes != null && bytes.length > 0) {
                return digestService.deserialize(bytes);
            }
        }
        return digestService.createDigest();
    }

    /**
     * 從文件中提取 digest bytes。
     */
    private byte[] extractDigestBytes(Object doc) {
        if (doc instanceof DailyUserUsage u) {
            return u.latencyDigest();
        } else if (doc instanceof DailyModelUsage m) {
            return m.latencyDigest();
        }
        return null;
    }

    /**
     * 計算延遲統計（用於 DailyUserUsage）。
     */
    private DailyUserUsage.LatencyStats calculateLatencyStats(TDigest digest) {
        if (digest.size() == 0) {
            return DailyUserUsage.LatencyStats.empty();
        }

        long count = (long) digest.size();
        long minMs = (long) digest.getMin();
        long maxMs = (long) digest.getMax();
        double avgMs = digest.quantile(0.5); // 使用 P50 作為平均的近似
        double p50Ms = digest.quantile(0.5);
        double p90Ms = digest.quantile(0.9);
        double p95Ms = digest.quantile(0.95);
        double p99Ms = digest.quantile(0.99);

        return new DailyUserUsage.LatencyStats(count, minMs, maxMs, avgMs, p50Ms, p90Ms, p95Ms, p99Ms);
    }

    /**
     * 計算延遲統計（用於 DailyModelUsage）。
     */
    private DailyModelUsage.LatencyStats calculateModelLatencyStats(TDigest digest) {
        if (digest.size() == 0) {
            return DailyModelUsage.LatencyStats.empty();
        }

        long count = (long) digest.size();
        long minMs = (long) digest.getMin();
        long maxMs = (long) digest.getMax();
        double avgMs = digest.quantile(0.5);
        double p50Ms = digest.quantile(0.5);
        double p90Ms = digest.quantile(0.9);
        double p95Ms = digest.quantile(0.95);
        double p99Ms = digest.quantile(0.99);

        return new DailyModelUsage.LatencyStats(count, minMs, maxMs, avgMs, p50Ms, p90Ms, p95Ms, p99Ms);
    }

    /**
     * 序列化 T-Digest。
     */
    private byte[] serializeDigest(TDigest digest) {
        return digestService.serialize(digest);
    }

    /**
     * 清理 MongoDB field name（移除不允許的字元）。
     */
    private String sanitizeFieldName(String name) {
        // MongoDB field names 不能包含 '.' 和 '$'
        return name.replace(".", "_").replace("$", "_");
    }
}
