package io.github.samzhu.ledger.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import io.github.samzhu.ledger.document.DailyModelUsage;
import io.github.samzhu.ledger.document.DailyUserUsage;
import io.github.samzhu.ledger.document.SystemStats;
import io.github.samzhu.ledger.document.UserSummary;
import io.github.samzhu.ledger.repository.DailyModelUsageRepository;
import io.github.samzhu.ledger.repository.DailyUserUsageRepository;
import io.github.samzhu.ledger.repository.SystemStatsRepository;
import io.github.samzhu.ledger.repository.UserSummaryRepository;

/**
 * 用量統計查詢服務。
 *
 * <p>提供各種維度的用量查詢，針對 Firestore/MongoDB 成本優化：
 * <ul>
 *   <li>使用 ID 批次查詢（$in operator）而非範圍查詢</li>
 *   <li>預先計算 document IDs 避免 collection scan</li>
 *   <li>支援分頁避免一次載入過多資料</li>
 * </ul>
 *
 * <p>查詢模式：
 * <pre>
 * 日期區間查詢：生成所有日期的 document IDs → findByIdIn()
 * 單一實體查詢：直接 findById()
 * 排行榜查詢：使用 index + limit
 * </pre>
 */
@Service
public class UsageQueryService {

    private static final Logger log = LoggerFactory.getLogger(UsageQueryService.class);

    private final DailyUserUsageRepository dailyUserUsageRepository;
    private final DailyModelUsageRepository dailyModelUsageRepository;
    private final UserSummaryRepository userSummaryRepository;
    private final SystemStatsRepository systemStatsRepository;

    public UsageQueryService(
            DailyUserUsageRepository dailyUserUsageRepository,
            DailyModelUsageRepository dailyModelUsageRepository,
            UserSummaryRepository userSummaryRepository,
            SystemStatsRepository systemStatsRepository) {
        this.dailyUserUsageRepository = dailyUserUsageRepository;
        this.dailyModelUsageRepository = dailyModelUsageRepository;
        this.userSummaryRepository = userSummaryRepository;
        this.systemStatsRepository = systemStatsRepository;
    }

    /**
     * 查詢用戶在指定期間的日用量。
     *
     * <p>使用 ID 批次查詢優化效能，避免範圍查詢的成本。
     *
     * @param userId 用戶 ID
     * @param startDate 起始日期（含）
     * @param endDate 結束日期（含）
     * @return 日用量列表
     */
    public List<DailyUserUsage> getUserDailyUsage(String userId, LocalDate startDate, LocalDate endDate) {
        List<String> docIds = generateDateUserIds(startDate, endDate, userId);
        log.debug("Querying user daily usage: userId={}, period={} to {}, docIds={}",
            userId, startDate, endDate, docIds.size());

        List<DailyUserUsage> results = dailyUserUsageRepository.findByIdIn(docIds);
        log.info("User daily usage query: userId={}, period={} to {}, found {} records",
            userId, startDate, endDate, results.size());

        return results;
    }

    /**
     * 查詢模型在指定期間的日用量。
     *
     * @param model 模型名稱
     * @param startDate 起始日期（含）
     * @param endDate 結束日期（含）
     * @return 日用量列表
     */
    public List<DailyModelUsage> getModelDailyUsage(String model, LocalDate startDate, LocalDate endDate) {
        List<String> docIds = generateDateModelIds(startDate, endDate, model);
        log.debug("Querying model daily usage: model={}, period={} to {}", model, startDate, endDate);

        List<DailyModelUsage> results = dailyModelUsageRepository.findByIdIn(docIds);
        log.info("Model daily usage query: model={}, period={} to {}, found {} records",
            model, startDate, endDate, results.size());

        return results;
    }

    /**
     * 查詢系統在指定期間的日統計。
     *
     * @param startDate 起始日期（含）
     * @param endDate 結束日期（含）
     * @return 系統日統計列表
     */
    public List<SystemStats> getSystemDailyStats(LocalDate startDate, LocalDate endDate) {
        List<String> docIds = generateDateIds(startDate, endDate);
        log.debug("Querying system daily stats: period={} to {}", startDate, endDate);

        List<SystemStats> results = systemStatsRepository.findByIdIn(docIds);
        log.info("System daily stats query: period={} to {}, found {} records",
            startDate, endDate, results.size());

        return results;
    }

    /**
     * 查詢用戶累計統計。
     *
     * @param userId 用戶 ID
     * @return 用戶統計（若存在）
     */
    public Optional<UserSummary> getUserSummary(String userId) {
        log.debug("Querying user summary: userId={}", userId);
        return userSummaryRepository.findById(userId);
    }

    /**
     * 查詢用量排行榜（依總 token 數）。
     *
     * @param limit 回傳筆數上限
     * @return 排序後的用戶統計列表
     */
    public List<UserSummary> getTopUsers(int limit) {
        log.debug("Querying top {} users by token usage", limit);
        return userSummaryRepository.findAllByOrderByTotalTokensDesc(PageRequest.of(0, limit));
    }

    /**
     * 查詢所有用戶統計。
     *
     * @return 所有用戶的統計列表
     */
    public List<UserSummary> getAllUsers() {
        log.debug("Querying all users");
        return userSummaryRepository.findAll();
    }

    /**
     * 生成用戶日用量的 document IDs。
     */
    private List<String> generateDateUserIds(LocalDate start, LocalDate end, String userId) {
        List<String> ids = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            ids.add(DailyUserUsage.createId(date, userId));
        }
        return ids;
    }

    /**
     * 生成模型日用量的 document IDs。
     */
    private List<String> generateDateModelIds(LocalDate start, LocalDate end, String model) {
        List<String> ids = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            ids.add(DailyModelUsage.createId(date, model));
        }
        return ids;
    }

    /**
     * 生成日期 IDs。
     */
    private List<String> generateDateIds(LocalDate start, LocalDate end) {
        List<String> ids = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            ids.add(date.toString());
        }
        return ids;
    }
}
