package io.github.samzhu.ledger.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.samzhu.ledger.document.DailyModelUsage;
import io.github.samzhu.ledger.document.DailyUserUsage;
import io.github.samzhu.ledger.document.SystemStats;
import io.github.samzhu.ledger.document.UserQuota;
import io.github.samzhu.ledger.dto.api.DailyUsage;
import io.github.samzhu.ledger.dto.api.DatePeriod;
import io.github.samzhu.ledger.dto.api.ModelUsageResponse;
import io.github.samzhu.ledger.dto.api.SystemUsageResponse;
import io.github.samzhu.ledger.dto.api.UsageSummary;
import io.github.samzhu.ledger.dto.api.UserUsageResponse;
import io.github.samzhu.ledger.service.BatchSettlementService;
import io.github.samzhu.ledger.service.EventBufferService;
import io.github.samzhu.ledger.service.UsageQueryService;

/**
 * 用量統計 REST API 控制器。
 *
 * <p>提供以下端點：
 * <ul>
 *   <li>{@code GET /api/v1/usage/users/{userId}/daily} - 用戶日用量</li>
 *   <li>{@code GET /api/v1/usage/models/{model}/daily} - 模型日用量</li>
 *   <li>{@code GET /api/v1/usage/system/daily} - 系統整體用量</li>
 *   <li>{@code GET /api/v1/usage/users} - 所有用戶統計</li>
 *   <li>{@code GET /api/v1/usage/users/{userId}} - 單一用戶統計</li>
 * </ul>
 *
 * <p>日期參數使用 ISO 格式：{@code YYYY-MM-DD}
 */
@RestController
@RequestMapping("/api/v1/usage")
public class UsageApiController {

    private static final Logger log = LoggerFactory.getLogger(UsageApiController.class);

    private final UsageQueryService queryService;
    private final BatchSettlementService settlementService;
    private final EventBufferService bufferService;

    public UsageApiController(UsageQueryService queryService,
                              BatchSettlementService settlementService,
                              EventBufferService bufferService) {
        this.queryService = queryService;
        this.settlementService = settlementService;
        this.bufferService = bufferService;
    }

    /**
     * 查詢用戶日用量。
     *
     * <p>端點：{@code GET /api/v1/usage/users/{userId}/daily?startDate=&endDate=}
     *
     * @param userId 用戶 ID
     * @param startDate 起始日期（含）
     * @param endDate 結束日期（含）
     * @return 用戶用量回應，包含摘要和每日明細
     */
    @GetMapping("/users/{userId}/daily")
    public ResponseEntity<UserUsageResponse> getUserDailyUsage(
            @PathVariable String userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        log.info("API request: getUserDailyUsage userId={}, period={} to {}", userId, startDate, endDate);

        List<DailyUserUsage> usages = queryService.getUserDailyUsage(userId, startDate, endDate);

        List<DailyUsage> daily = usages.stream()
            .map(u -> new DailyUsage(
                u.date(),
                u.totalInputTokens(),
                u.totalOutputTokens(),
                u.totalTokens(),
                u.requestCount(),
                u.estimatedCostUsd()
            ))
            .sorted((a, b) -> a.date().compareTo(b.date()))
            .toList();

        UsageSummary summary = new UsageSummary(
            daily.stream().mapToLong(DailyUsage::inputTokens).sum(),
            daily.stream().mapToLong(DailyUsage::outputTokens).sum(),
            daily.stream().mapToLong(DailyUsage::totalTokens).sum(),
            daily.stream().mapToInt(DailyUsage::requests).sum(),
            daily.stream().map(DailyUsage::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add)
        );

        log.debug("getUserDailyUsage response: {} days, {} total tokens", daily.size(), summary.totalTokens());

        return ResponseEntity.ok(new UserUsageResponse(
            userId,
            new DatePeriod(startDate, endDate),
            summary,
            daily
        ));
    }

    /**
     * 查詢模型日用量。
     *
     * <p>端點：{@code GET /api/v1/usage/models/{model}/daily?startDate=&endDate=}
     *
     * @param model 模型名稱
     * @param startDate 起始日期（含）
     * @param endDate 結束日期（含）
     * @return 模型用量回應，包含摘要和每日明細
     */
    @GetMapping("/models/{model}/daily")
    public ResponseEntity<ModelUsageResponse> getModelDailyUsage(
            @PathVariable String model,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        log.info("API request: getModelDailyUsage model={}, period={} to {}", model, startDate, endDate);

        List<DailyModelUsage> usages = queryService.getModelDailyUsage(model, startDate, endDate);

        List<DailyUsage> daily = usages.stream()
            .map(u -> new DailyUsage(
                u.date(),
                u.totalInputTokens(),
                u.totalOutputTokens(),
                u.totalTokens(),
                u.requestCount(),
                u.estimatedCostUsd()
            ))
            .sorted((a, b) -> a.date().compareTo(b.date()))
            .toList();

        UsageSummary summary = new UsageSummary(
            daily.stream().mapToLong(DailyUsage::inputTokens).sum(),
            daily.stream().mapToLong(DailyUsage::outputTokens).sum(),
            daily.stream().mapToLong(DailyUsage::totalTokens).sum(),
            daily.stream().mapToInt(DailyUsage::requests).sum(),
            daily.stream().map(DailyUsage::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add)
        );

        log.debug("getModelDailyUsage response: {} days, {} total tokens", daily.size(), summary.totalTokens());

        return ResponseEntity.ok(new ModelUsageResponse(
            model,
            new DatePeriod(startDate, endDate),
            summary,
            daily
        ));
    }

    /**
     * 查詢系統整體日用量。
     *
     * <p>端點：{@code GET /api/v1/usage/system/daily?startDate=&endDate=}
     *
     * @param startDate 起始日期（含）
     * @param endDate 結束日期（含）
     * @return 系統用量回應，包含摘要、每日明細和排行榜
     */
    @GetMapping("/system/daily")
    public ResponseEntity<SystemUsageResponse> getSystemDailyUsage(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        log.info("API request: getSystemDailyUsage period={} to {}", startDate, endDate);

        List<SystemStats> stats = queryService.getSystemDailyStats(startDate, endDate);
        List<UserQuota> topUsers = queryService.getTopUsers(10);

        List<DailyUsage> daily = stats.stream()
            .map(s -> new DailyUsage(
                s.date(),
                s.totalInputTokens(),
                s.totalOutputTokens(),
                s.totalTokens(),
                s.totalRequestCount(),
                s.totalEstimatedCostUsd()
            ))
            .sorted((a, b) -> a.date().compareTo(b.date()))
            .toList();

        UsageSummary summary = new UsageSummary(
            daily.stream().mapToLong(DailyUsage::inputTokens).sum(),
            daily.stream().mapToLong(DailyUsage::outputTokens).sum(),
            daily.stream().mapToLong(DailyUsage::totalTokens).sum(),
            daily.stream().mapToInt(DailyUsage::requests).sum(),
            daily.stream().map(DailyUsage::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add)
        );

        List<SystemUsageResponse.TopItem> topUserItems = topUsers.stream()
            .map(u -> new SystemUsageResponse.TopItem(
                u.userId(), u.userId(), u.totalRequestCount(), u.totalTokens()))
            .toList();

        log.debug("getSystemDailyUsage response: {} days, {} total tokens, {} top users",
            daily.size(), summary.totalTokens(), topUserItems.size());

        return ResponseEntity.ok(new SystemUsageResponse(
            new DatePeriod(startDate, endDate),
            summary,
            daily,
            topUserItems,
            List.of()
        ));
    }

    /**
     * 查詢所有用戶配額與統計。
     *
     * <p>端點：{@code GET /api/v1/usage/users}
     *
     * @return 所有用戶的配額與累計統計列表
     */
    @GetMapping("/users")
    public ResponseEntity<List<UserQuota>> getAllUsers() {
        log.info("API request: getAllUsers");
        List<UserQuota> users = queryService.getAllUsers();
        log.debug("getAllUsers response: {} users", users.size());
        return ResponseEntity.ok(users);
    }

    /**
     * 查詢單一用戶配額與統計。
     *
     * <p>端點：{@code GET /api/v1/usage/users/{userId}}
     *
     * @param userId 用戶 ID
     * @return 用戶配額與累計統計，若不存在回傳 404
     */
    @GetMapping("/users/{userId}")
    public ResponseEntity<UserQuota> getUserQuota(@PathVariable String userId) {
        log.info("API request: getUserQuota userId={}", userId);
        return queryService.getUserQuota(userId)
            .map(quota -> {
                log.debug("getUserQuota found: userId={}, totalTokens={}", userId, quota.totalTokens());
                return ResponseEntity.ok(quota);
            })
            .orElseGet(() -> {
                log.debug("getUserQuota not found: userId={}", userId);
                return ResponseEntity.notFound().build();
            });
    }

    /**
     * 手動觸發批次寫入（Flush）。
     *
     * <p>端點：{@code POST /api/v1/usage/flush/trigger}
     *
     * <p>將記憶體中的事件緩衝區寫入 RawEventBatch。
     *
     * @return 刷新結果
     */
    @PostMapping("/flush/trigger")
    public ResponseEntity<FlushResult> triggerFlush() {
        log.info("API request: triggerFlush (manual)");
        bufferService.flushBuffer();
        log.info("Manual flush completed");
        return ResponseEntity.ok(new FlushResult("Flush completed"));
    }

    /**
     * 手動觸發批次結算。
     *
     * <p>端點：{@code POST /api/v1/usage/settlement/trigger}
     *
     * <p>處理所有 {@code processed=false} 的 RawEventBatch，
     * 執行聚合統計並更新 system_stats、daily_user_usage、daily_model_usage。
     *
     * @return 處理的批次數量
     */
    @PostMapping("/settlement/trigger")
    public ResponseEntity<SettlementResult> triggerSettlement() {
        log.info("API request: triggerSettlement (manual)");
        int processedBatches = settlementService.triggerSettlement();
        log.info("Manual settlement completed: {} batches processed", processedBatches);
        return ResponseEntity.ok(new SettlementResult(processedBatches, "Settlement completed"));
    }

    /**
     * 手動觸發完整處理流程：先 Flush 再 Settlement。
     *
     * <p>端點：{@code POST /api/v1/usage/process/trigger}
     *
     * <p>執行順序：
     * <ol>
     *   <li>Flush - 將記憶體緩衝區寫入 RawEventBatch</li>
     *   <li>Settlement - 處理所有未結算的 RawEventBatch</li>
     * </ol>
     *
     * @return 處理結果
     */
    @PostMapping("/process/trigger")
    public ResponseEntity<ProcessResult> triggerProcess() {
        log.info("API request: triggerProcess (manual flush + settlement)");

        // Step 1: Flush buffer
        bufferService.flushBuffer();
        log.info("Step 1/2: Flush completed");

        // Step 2: Settlement
        int processedBatches = settlementService.triggerSettlement();
        log.info("Step 2/2: Settlement completed, {} batches processed", processedBatches);

        return ResponseEntity.ok(new ProcessResult(processedBatches, "Process completed (flush + settlement)"));
    }

    /**
     * Flush 結果回應。
     */
    public record FlushResult(String message) {}

    /**
     * 結算結果回應。
     */
    public record SettlementResult(int processedBatches, String message) {}

    /**
     * 完整處理結果回應。
     */
    public record ProcessResult(int processedBatches, String message) {}
}
