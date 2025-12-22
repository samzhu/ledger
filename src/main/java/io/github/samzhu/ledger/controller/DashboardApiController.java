package io.github.samzhu.ledger.controller;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.samzhu.ledger.document.UserQuota;
import io.github.samzhu.ledger.dto.api.DailyModelUsageApiDto;
import io.github.samzhu.ledger.dto.api.DailyUserUsageApiDto;
import io.github.samzhu.ledger.dto.api.SystemStatsApiDto;
import io.github.samzhu.ledger.service.UsageQueryService;
import io.github.samzhu.ledger.service.UsageQueryService.ModelSummary;

/**
 * Dashboard data API controller for Vue 3 frontend.
 *
 * <p>Provides REST API endpoints for dashboard pages:
 * <ul>
 *   <li>{@code GET /api/v1/dashboard/overview} - System overview data</li>
 *   <li>{@code GET /api/v1/dashboard/users} - All users list</li>
 *   <li>{@code GET /api/v1/dashboard/users/{userId}} - User detail with daily usage</li>
 *   <li>{@code GET /api/v1/dashboard/models} - All models summary</li>
 *   <li>{@code GET /api/v1/dashboard/models/{modelName}} - Model detail with daily usage</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardApiController {

    private static final Logger log = LoggerFactory.getLogger(DashboardApiController.class);

    private final UsageQueryService queryService;

    public DashboardApiController(UsageQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * System overview data.
     *
     * <p>Returns hourly data with ISO 8601 UTC timestamps for unambiguous timezone handling.
     */
    @GetMapping("/overview")
    public ResponseEntity<OverviewResponse> getOverview(
            @RequestParam(defaultValue = "7") int days) {

        LocalDate endDate = LocalDate.now(ZoneId.of("UTC"));
        LocalDate startDate = endDate.minusDays(days - 1);

        log.debug("API request: overview, days={}", days);

        // Convert to DTOs with ISO 8601 hourly format
        List<SystemStatsApiDto> stats = queryService.getSystemDailyStats(startDate, endDate)
            .stream()
            .map(SystemStatsApiDto::from)
            .toList();
        List<UserQuota> topUsers = queryService.getTopUsers(10);

        return ResponseEntity.ok(new OverviewResponse(
            startDate.toString(),
            endDate.toString(),
            days,
            stats,
            topUsers
        ));
    }

    /**
     * All users list.
     */
    @GetMapping("/users")
    public ResponseEntity<UsersResponse> getUsers() {
        log.debug("API request: users list");

        List<UserQuota> users = queryService.getAllUsers();

        return ResponseEntity.ok(new UsersResponse(users));
    }

    /**
     * User detail with daily usage.
     *
     * <p>Returns hourly data with ISO 8601 UTC timestamps for unambiguous timezone handling.
     */
    @GetMapping("/users/{userId}")
    public ResponseEntity<UserDetailResponse> getUserDetail(
            @PathVariable String userId,
            @RequestParam(defaultValue = "7") int days) {

        LocalDate endDate = LocalDate.now(ZoneId.of("UTC"));
        LocalDate startDate = endDate.minusDays(days - 1);

        log.debug("API request: user detail, userId={}, days={}", userId, days);

        // Convert to DTOs with ISO 8601 hourly format
        List<DailyUserUsageApiDto> usages = queryService.getUserDailyUsage(userId, startDate, endDate)
            .stream()
            .map(DailyUserUsageApiDto::from)
            .toList();
        UserQuota quota = queryService.getUserQuota(userId).orElse(null);

        return ResponseEntity.ok(new UserDetailResponse(
            userId,
            startDate.toString(),
            endDate.toString(),
            days,
            quota,
            usages
        ));
    }

    /**
     * All models summary.
     */
    @GetMapping("/models")
    public ResponseEntity<ModelsResponse> getModels(
            @RequestParam(defaultValue = "7") int days) {

        LocalDate endDate = LocalDate.now(ZoneId.of("UTC"));
        LocalDate startDate = endDate.minusDays(days - 1);

        log.debug("API request: models list, days={}", days);

        List<ModelSummary> models = queryService.getAllModels(days);

        return ResponseEntity.ok(new ModelsResponse(
            startDate.toString(),
            endDate.toString(),
            days,
            models
        ));
    }

    /**
     * Model detail with daily usage.
     *
     * <p>Returns hourly data with ISO 8601 UTC timestamps for unambiguous timezone handling.
     */
    @GetMapping("/models/{modelName}")
    public ResponseEntity<ModelDetailResponse> getModelDetail(
            @PathVariable String modelName,
            @RequestParam(defaultValue = "7") int days) {

        LocalDate endDate = LocalDate.now(ZoneId.of("UTC"));
        LocalDate startDate = endDate.minusDays(days - 1);

        log.debug("API request: model detail, modelName={}, days={}", modelName, days);

        // Convert to DTOs with ISO 8601 hourly format
        List<DailyModelUsageApiDto> usages = queryService.getModelDailyUsage(modelName, startDate, endDate)
            .stream()
            .map(DailyModelUsageApiDto::from)
            .toList();

        return ResponseEntity.ok(new ModelDetailResponse(
            modelName,
            startDate.toString(),
            endDate.toString(),
            days,
            usages
        ));
    }

    // Response records

    public record OverviewResponse(
        String startDate,
        String endDate,
        int days,
        List<SystemStatsApiDto> stats,
        List<UserQuota> topUsers
    ) {}

    public record UsersResponse(
        List<UserQuota> users
    ) {}

    public record UserDetailResponse(
        String userId,
        String startDate,
        String endDate,
        int days,
        UserQuota quota,
        List<DailyUserUsageApiDto> usages
    ) {}

    public record ModelsResponse(
        String startDate,
        String endDate,
        int days,
        List<ModelSummary> models
    ) {}

    public record ModelDetailResponse(
        String modelName,
        String startDate,
        String endDate,
        int days,
        List<DailyModelUsageApiDto> usages
    ) {}
}
