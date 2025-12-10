package io.github.samzhu.ledger.controller;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import io.github.samzhu.ledger.document.DailyUserUsage;
import io.github.samzhu.ledger.document.SystemStats;
import io.github.samzhu.ledger.document.UserSummary;
import io.github.samzhu.ledger.service.UsageQueryService;

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
     * <p>顯示最近 N 天的系統整體用量和高用量用戶排行榜。
     *
     * @param model Spring MVC Model
     * @param days 顯示天數（預設 7 天）
     * @return 視圖名稱
     */
    @GetMapping
    public String overview(Model model,
            @RequestParam(defaultValue = "7") int days) {

        log.info("Dashboard request: overview, days={}", days);

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days - 1);

        List<SystemStats> stats = queryService.getSystemDailyStats(startDate, endDate);
        List<UserSummary> topUsers = queryService.getTopUsers(10);

        model.addAttribute("currentPage", "overview");
        model.addAttribute("pageTitle", "System Overview");
        model.addAttribute("stats", stats);
        model.addAttribute("topUsers", topUsers);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("days", days);

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

        List<UserSummary> users = queryService.getAllUsers();

        model.addAttribute("currentPage", "users");
        model.addAttribute("pageTitle", "User Usage");
        model.addAttribute("users", users);

        log.debug("Users page loaded: {} users", users.size());

        return "dashboard/users";
    }

    /**
     * 用戶詳情頁面。
     *
     * <p>顯示單一用戶的累計統計和最近 N 天的每日用量。
     *
     * @param userId 用戶 ID
     * @param days 顯示天數（預設 7 天）
     * @param model Spring MVC Model
     * @return 視圖名稱
     */
    @GetMapping("/users/{userId}")
    public String userDetail(@PathVariable String userId,
            @RequestParam(defaultValue = "7") int days,
            Model model) {

        log.info("Dashboard request: userDetail userId={}, days={}", userId, days);

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days - 1);

        List<DailyUserUsage> usages = queryService.getUserDailyUsage(userId, startDate, endDate);
        UserSummary summary = queryService.getUserSummary(userId).orElse(null);

        model.addAttribute("currentPage", "users");
        model.addAttribute("pageTitle", "User: " + userId);
        model.addAttribute("userId", userId);
        model.addAttribute("usages", usages);
        model.addAttribute("summary", summary);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("days", days);

        log.debug("User detail loaded: userId={}, {} daily records", userId, usages.size());

        return "dashboard/user-detail";
    }

    /**
     * 模型用量頁面。
     *
     * @param model Spring MVC Model
     * @param days 顯示天數（預設 7 天）
     * @return 視圖名稱
     */
    @GetMapping("/models")
    public String models(Model model,
            @RequestParam(defaultValue = "7") int days) {

        log.info("Dashboard request: models, days={}", days);

        model.addAttribute("currentPage", "models");
        model.addAttribute("pageTitle", "Model Usage");
        model.addAttribute("days", days);

        return "dashboard/models";
    }
}
