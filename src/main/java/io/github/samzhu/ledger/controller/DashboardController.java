package io.github.samzhu.ledger.controller;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import io.github.samzhu.ledger.document.DailyModelUsage;
import io.github.samzhu.ledger.document.DailyUserUsage;
import io.github.samzhu.ledger.document.SystemStats;
import io.github.samzhu.ledger.document.UserQuota;
import io.github.samzhu.ledger.service.UsageQueryService;
import io.github.samzhu.ledger.service.UsageQueryService.ModelSummary;

/**
 * 儀表板 UI 控制器。
 *
 * <p>提供 Server-Side Rendered 的管理介面頁面：
 * <ul>
 *   <li>{@code GET /dashboard} - 系統概覽</li>
 *   <li>{@code GET /dashboard/users} - 用戶列表</li>
 *   <li>{@code GET /dashboard/users/{userId}} - 用戶詳情</li>
 *   <li>{@code GET /dashboard/models} - 模型用量</li>
 * </ul>
 *
 * <p>使用 Thymeleaf 模板引擎渲染頁面。
 */
@Controller
@RequestMapping("/dashboard")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final UsageQueryService queryService;

    public DashboardController(UsageQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 系統概覽頁面。
     *
     * <p>顯示指定日期範圍的系統整體用量和高用量用戶排行榜。
     * 支援兩種日期指定方式：
     * <ul>
     *   <li>startDate/endDate - 明確指定日期範圍（優先使用，支援用戶時區）</li>
     *   <li>days - 從今天往前算 N 天（向後相容，使用服務器時區）</li>
     * </ul>
     *
     * @param model Spring MVC Model
     * @param days 顯示天數（預設 7 天，當 startDate/endDate 未指定時使用）
     * @param startDate 起始日期（ISO 格式 YYYY-MM-DD）
     * @param endDate 結束日期（ISO 格式 YYYY-MM-DD）
     * @param timezone 用戶時區（如 Asia/Taipei），用於 days 計算
     * @return 視圖名稱
     */
    @GetMapping
    public String overview(Model model,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String timezone) {

        // 計算日期範圍
        LocalDate[] dateRange = calculateDateRange(startDate, endDate, days, timezone);
        startDate = dateRange[0];
        endDate = dateRange[1];

        log.info("Dashboard request: overview, dateRange={} to {}, days={}", startDate, endDate, days);

        List<SystemStats> stats = queryService.getSystemDailyStats(startDate, endDate);
        List<UserQuota> topUsers = queryService.getTopUsers(10);

        // Pre-compute summary statistics (SpEL doesn't support lambdas)
        int totalRequests = stats.stream().mapToInt(SystemStats::totalRequestCount).sum();
        long totalTokens = stats.stream().mapToLong(SystemStats::totalTokens).sum();
        long totalInputTokens = stats.stream().mapToLong(SystemStats::totalInputTokens).sum();
        long totalOutputTokens = stats.stream().mapToLong(SystemStats::totalOutputTokens).sum();
        java.math.BigDecimal totalCost = stats.stream()
            .map(SystemStats::totalEstimatedCostUsd)
            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        java.math.BigDecimal avgDailyCost = stats.isEmpty() ? java.math.BigDecimal.ZERO
            : totalCost.divide(java.math.BigDecimal.valueOf(stats.size()), 2, java.math.RoundingMode.HALF_UP);
        double avgLatency = stats.stream().mapToDouble(SystemStats::avgLatencyMs).average().orElse(0);
        double p50Latency = stats.stream().mapToDouble(SystemStats::p50LatencyMs).average().orElse(0);
        double p90Latency = stats.stream().mapToDouble(SystemStats::p90LatencyMs).average().orElse(0);
        double p99Latency = stats.stream().mapToDouble(SystemStats::p99LatencyMs).average().orElse(0);
        java.math.BigDecimal cacheSavings = stats.stream()
            .map(SystemStats::systemCacheSavedUsd)
            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        double avgCacheHitRate = stats.stream().mapToDouble(SystemStats::systemCacheHitRate).average().orElse(0);

        // Get the last day's stats for display (SpEL has trouble with list index access)
        SystemStats lastDayStats = stats.isEmpty() ? null : stats.get(stats.size() - 1);
        double lastDaySuccessRate = lastDayStats != null ? lastDayStats.successRate() : 0;
        int lastDayPeakHour = lastDayStats != null ? lastDayStats.peakHour() : 0;
        int lastDayPeakHourRequests = lastDayStats != null ? lastDayStats.peakHourRequests() : 0;
        var lastDayTopModels = lastDayStats != null ? lastDayStats.topModels() : java.util.Collections.emptyList();

        model.addAttribute("currentPage", "overview");
        model.addAttribute("pageTitle", "System Overview");
        model.addAttribute("stats", stats);
        model.addAttribute("topUsers", topUsers);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("days", days);

        // Add pre-computed values for template
        model.addAttribute("summaryTotalRequests", totalRequests);
        model.addAttribute("summaryTotalTokens", totalTokens);
        model.addAttribute("summaryTotalInputTokens", totalInputTokens);
        model.addAttribute("summaryTotalOutputTokens", totalOutputTokens);
        model.addAttribute("summaryTotalCost", totalCost);
        model.addAttribute("summaryAvgDailyCost", avgDailyCost);
        model.addAttribute("summaryAvgLatency", avgLatency);
        model.addAttribute("summaryP50Latency", p50Latency);
        model.addAttribute("summaryP90Latency", p90Latency);
        model.addAttribute("summaryP99Latency", p99Latency);
        model.addAttribute("summaryCacheSavings", cacheSavings);
        model.addAttribute("summaryAvgCacheHitRate", avgCacheHitRate);

        // Add last day stats for template
        model.addAttribute("lastDayStats", lastDayStats);
        model.addAttribute("lastDaySuccessRate", lastDaySuccessRate);
        model.addAttribute("lastDayPeakHour", lastDayPeakHour);
        model.addAttribute("lastDayPeakHourRequests", lastDayPeakHourRequests);
        model.addAttribute("lastDayTopModels", lastDayTopModels);

        log.debug("Overview loaded: {} stats records, {} top users", stats.size(), topUsers.size());

        return "dashboard/overview";
    }

    /**
     * 用戶列表頁面。
     *
     * <p>顯示所有用戶的累計用量統計。
     *
     * @param model Spring MVC Model
     * @return 視圖名稱
     */
    @GetMapping("/users")
    public String users(Model model) {
        log.info("Dashboard request: users");

        List<UserQuota> users = queryService.getAllUsers();

        // Pre-compute summary statistics (SpEL doesn't support lambdas)
        long usersQuotaEnabledCount = users.stream()
            .filter(u -> u.quotaConfig() != null && u.quotaConfig().enabled())
            .count();
        long usersQuotaExceededCount = users.stream()
            .filter(UserQuota::quotaExceeded)
            .count();
        long usersTotalTokens = users.stream().mapToLong(UserQuota::totalTokens).sum();
        java.math.BigDecimal usersTotalCost = users.stream()
            .map(UserQuota::totalEstimatedCostUsd)
            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        model.addAttribute("currentPage", "users");
        model.addAttribute("pageTitle", "User Usage");
        model.addAttribute("users", users);

        // Add pre-computed values for template
        model.addAttribute("usersQuotaEnabledCount", usersQuotaEnabledCount);
        model.addAttribute("usersQuotaExceededCount", usersQuotaExceededCount);
        model.addAttribute("usersTotalTokens", usersTotalTokens);
        model.addAttribute("usersTotalCost", usersTotalCost);

        log.debug("Users page loaded: {} users", users.size());

        return "dashboard/users";
    }

    /**
     * 用戶詳情頁面。
     *
     * <p>顯示單一用戶的累計統計和指定日期範圍的每日用量。
     *
     * @param userId 用戶 ID
     * @param days 顯示天數（預設 7 天，當 startDate/endDate 未指定時使用）
     * @param startDate 起始日期（ISO 格式 YYYY-MM-DD）
     * @param endDate 結束日期（ISO 格式 YYYY-MM-DD）
     * @param timezone 用戶時區（如 Asia/Taipei）
     * @param model Spring MVC Model
     * @return 視圖名稱
     */
    @GetMapping("/users/{userId}")
    public String userDetail(@PathVariable String userId,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String timezone,
            Model model) {

        // 計算日期範圍
        LocalDate[] dateRange = calculateDateRange(startDate, endDate, days, timezone);
        startDate = dateRange[0];
        endDate = dateRange[1];

        log.info("Dashboard request: userDetail userId={}, dateRange={} to {}", userId, startDate, endDate);

        List<DailyUserUsage> usages = queryService.getUserDailyUsage(userId, startDate, endDate);
        UserQuota quota = queryService.getUserQuota(userId).orElse(null);

        model.addAttribute("currentPage", "users");
        model.addAttribute("pageTitle", "User: " + userId);
        model.addAttribute("userId", userId);
        model.addAttribute("usages", usages);
        model.addAttribute("quota", quota);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("days", days);

        log.debug("User detail loaded: userId={}, {} daily records", userId, usages.size());

        return "dashboard/user-detail";
    }

    /**
     * 模型用量頁面。
     *
     * <p>顯示所有 LLM 模型的使用統計列表。
     *
     * @param model Spring MVC Model
     * @param days 顯示天數（預設 7 天，當 startDate/endDate 未指定時使用）
     * @param startDate 起始日期（ISO 格式 YYYY-MM-DD）
     * @param endDate 結束日期（ISO 格式 YYYY-MM-DD）
     * @param timezone 用戶時區（如 Asia/Taipei）
     * @return 視圖名稱
     */
    @GetMapping("/models")
    public String models(Model model,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String timezone) {

        // 計算日期範圍
        LocalDate[] dateRange = calculateDateRange(startDate, endDate, days, timezone);
        startDate = dateRange[0];
        endDate = dateRange[1];

        log.info("Dashboard request: models, dateRange={} to {}", startDate, endDate);

        List<ModelSummary> modelSummaries = queryService.getAllModels(days);

        // Pre-compute summary statistics (SpEL doesn't support lambdas)
        int modelsTotalRequests = modelSummaries.stream().mapToInt(ModelSummary::totalRequestCount).sum();
        long modelsTotalTokens = modelSummaries.stream().mapToLong(ModelSummary::totalTokens).sum();
        java.math.BigDecimal modelsTotalCost = modelSummaries.stream()
            .map(ModelSummary::totalEstimatedCostUsd)
            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        model.addAttribute("currentPage", "models");
        model.addAttribute("pageTitle", "Model Usage");
        model.addAttribute("models", modelSummaries);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("days", days);

        // Add pre-computed values for template
        model.addAttribute("modelsTotalRequests", modelsTotalRequests);
        model.addAttribute("modelsTotalTokens", modelsTotalTokens);
        model.addAttribute("modelsTotalCost", modelsTotalCost);

        log.debug("Models page loaded: {} models", modelSummaries.size());

        return "dashboard/models";
    }

    /**
     * 模型詳情頁面。
     *
     * <p>顯示單一模型的詳細用量統計和趨勢圖表。
     *
     * @param modelName 模型名稱
     * @param days 顯示天數（預設 7 天，當 startDate/endDate 未指定時使用）
     * @param startDate 起始日期（ISO 格式 YYYY-MM-DD）
     * @param endDate 結束日期（ISO 格式 YYYY-MM-DD）
     * @param timezone 用戶時區（如 Asia/Taipei）
     * @param model Spring MVC Model
     * @return 視圖名稱
     */
    @GetMapping("/models/{modelName}")
    public String modelDetail(@PathVariable String modelName,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String timezone,
            Model model) {

        // 計算日期範圍
        LocalDate[] dateRange = calculateDateRange(startDate, endDate, days, timezone);
        startDate = dateRange[0];
        endDate = dateRange[1];

        log.info("Dashboard request: modelDetail modelName={}, dateRange={} to {}", modelName, startDate, endDate);

        List<DailyModelUsage> usages = queryService.getModelDailyUsage(modelName, startDate, endDate);

        // 計算摘要統計
        long totalInputTokens = usages.stream().mapToLong(DailyModelUsage::totalInputTokens).sum();
        long totalOutputTokens = usages.stream().mapToLong(DailyModelUsage::totalOutputTokens).sum();
        long totalTokens = usages.stream().mapToLong(DailyModelUsage::totalTokens).sum();
        int totalRequests = usages.stream().mapToInt(DailyModelUsage::requestCount).sum();
        int totalSuccess = usages.stream().mapToInt(DailyModelUsage::successCount).sum();
        int maxUniqueUsers = usages.stream().mapToInt(DailyModelUsage::uniqueUsers).max().orElse(0);
        java.math.BigDecimal totalCost = usages.stream()
            .map(DailyModelUsage::estimatedCostUsd)
            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        model.addAttribute("currentPage", "models");
        model.addAttribute("pageTitle", "Model: " + modelName);
        model.addAttribute("modelName", modelName);
        model.addAttribute("usages", usages);
        model.addAttribute("totalInputTokens", totalInputTokens);
        model.addAttribute("totalOutputTokens", totalOutputTokens);
        model.addAttribute("totalTokens", totalTokens);
        model.addAttribute("totalRequests", totalRequests);
        model.addAttribute("totalSuccess", totalSuccess);
        model.addAttribute("uniqueUsers", maxUniqueUsers);
        model.addAttribute("totalCost", totalCost);
        model.addAttribute("successRate", totalRequests > 0 ? (double) totalSuccess / totalRequests * 100.0 : 0.0);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("days", days);

        log.debug("Model detail loaded: modelName={}, {} daily records", modelName, usages.size());

        return "dashboard/model-detail";
    }

    /**
     * 計算日期範圍。
     *
     * <p>優先使用明確指定的 startDate/endDate，
     * 若未指定則根據 days 和 timezone 計算。
     *
     * @param startDate 起始日期（可為 null）
     * @param endDate 結束日期（可為 null）
     * @param days 天數（當日期未指定時使用）
     * @param timezone 時區 ID（如 "Asia/Taipei"），null 時使用 UTC
     * @return [startDate, endDate] 陣列
     */
    private LocalDate[] calculateDateRange(LocalDate startDate, LocalDate endDate, int days, String timezone) {
        // 如果明確指定了日期範圍，直接使用
        if (startDate != null && endDate != null) {
            return new LocalDate[] { startDate, endDate };
        }

        // 根據時區計算「今天」
        ZoneId zone = parseTimezone(timezone);
        LocalDate today = LocalDate.now(zone);

        // 計算日期範圍
        if (endDate == null) {
            endDate = today;
        }
        if (startDate == null) {
            startDate = endDate.minusDays(days - 1);
        }

        return new LocalDate[] { startDate, endDate };
    }

    /**
     * 解析時區 ID。
     *
     * @param timezone 時區 ID（如 "Asia/Taipei"）
     * @return ZoneId，若無效則回傳 UTC
     */
    private ZoneId parseTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            log.warn("Invalid timezone '{}', using UTC", timezone);
            return ZoneId.of("UTC");
        }
    }
}
