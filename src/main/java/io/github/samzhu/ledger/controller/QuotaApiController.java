package io.github.samzhu.ledger.controller;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.samzhu.ledger.document.BonusRecord;
import io.github.samzhu.ledger.document.QuotaHistory;
import io.github.samzhu.ledger.document.UserQuota;
import io.github.samzhu.ledger.dto.api.BonusGrantRequest;
import io.github.samzhu.ledger.dto.api.BonusHistoryResponse;
import io.github.samzhu.ledger.dto.api.QuotaConfigRequest;
import io.github.samzhu.ledger.dto.api.QuotaHistoryResponse;
import io.github.samzhu.ledger.dto.api.QuotaStatusResponse;
import io.github.samzhu.ledger.repository.QuotaHistoryRepository;
import io.github.samzhu.ledger.repository.UserQuotaRepository;
import io.github.samzhu.ledger.service.BonusService;

/**
 * 配額管理 API 控制器。
 *
 * <p>提供配額狀態查詢、配額設定、額外額度給予等 API 端點。
 */
@RestController
@RequestMapping("/api/v1/quota")
public class QuotaApiController {

    private static final Logger log = LoggerFactory.getLogger(QuotaApiController.class);

    private final UserQuotaRepository userQuotaRepository;
    private final QuotaHistoryRepository quotaHistoryRepository;
    private final BonusService bonusService;

    public QuotaApiController(
            UserQuotaRepository userQuotaRepository,
            QuotaHistoryRepository quotaHistoryRepository,
            BonusService bonusService) {
        this.userQuotaRepository = userQuotaRepository;
        this.quotaHistoryRepository = quotaHistoryRepository;
        this.bonusService = bonusService;
    }

    // ========== 配額狀態查詢 ==========

    /**
     * 取得用戶配額狀態。
     *
     * @param userId 用戶 ID
     * @return 配額狀態
     */
    @GetMapping("/users/{userId}")
    public ResponseEntity<QuotaStatusResponse> getUserQuota(@PathVariable String userId) {
        log.debug("Getting quota status for user: {}", userId);

        return userQuotaRepository.findByUserId(userId)
            .map(QuotaStatusResponse::fromUserQuota)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 取得所有用戶配額列表（分頁）。
     *
     * @param page 頁碼（從 0 開始）
     * @param size 每頁數量
     * @param exceeded 是否只查詢超額用戶
     * @return 用戶配額列表
     */
    @GetMapping("/users")
    public ResponseEntity<Page<QuotaStatusResponse>> getAllUserQuotas(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Boolean exceeded) {

        log.debug("Getting all user quotas: page={}, size={}, exceeded={}", page, size, exceeded);

        Page<UserQuota> quotaPage;
        if (Boolean.TRUE.equals(exceeded)) {
            quotaPage = userQuotaRepository.findByQuotaEnabledTrueOrderByCostUsagePercentDesc(
                PageRequest.of(page, size));
        } else {
            quotaPage = userQuotaRepository.findAllByOrderByLastActiveAtDesc(
                PageRequest.of(page, size));
        }

        Page<QuotaStatusResponse> responsePage = quotaPage.map(QuotaStatusResponse::fromUserQuota);
        return ResponseEntity.ok(responsePage);
    }

    /**
     * 取得已超額的用戶列表。
     *
     * @return 已超額的用戶列表
     */
    @GetMapping("/users/exceeded")
    public ResponseEntity<List<QuotaStatusResponse>> getExceededUsers() {
        log.debug("Getting exceeded users");

        List<QuotaStatusResponse> responses = userQuotaRepository.findEnabledAndExceeded()
            .stream()
            .map(QuotaStatusResponse::fromUserQuota)
            .toList();

        return ResponseEntity.ok(responses);
    }

    // ========== 配額管理 ==========

    /**
     * 設定用戶配額。
     *
     * @param userId 用戶 ID
     * @param request 配額設定請求
     * @return 更新後的配額狀態
     */
    @PutMapping("/users/{userId}/config")
    public ResponseEntity<QuotaStatusResponse> updateQuotaConfig(
            @PathVariable String userId,
            @RequestBody @Validated QuotaConfigRequest request) {

        log.info("Updating quota config for user: {} -> enabled={}, limit={}",
            userId, request.enabled(), request.costLimitUsd());

        // 檢查用戶是否存在
        if (!userQuotaRepository.existsByUserId(userId)) {
            return ResponseEntity.notFound().build();
        }

        // 更新配額設定
        userQuotaRepository.updateQuotaSettingsByUserId(
            userId,
            request.enabled(),
            request.costLimitUsd(),
            Instant.now()
        );

        // 重算使用率（如果啟用配額）
        UserQuota quota = userQuotaRepository.findByUserId(userId).orElse(null);
        if (quota != null && request.enabled()) {
            double usagePercent = UserQuota.calculateCostUsagePercent(
                quota.periodCostUsd(),
                request.costLimitUsd().add(
                    quota.bonusCostUsd() != null ? quota.bonusCostUsd() : java.math.BigDecimal.ZERO
                )
            );
            boolean exceeded = usagePercent >= 100;
            userQuotaRepository.updateQuotaStatusByUserId(userId, usagePercent, exceeded, Instant.now());
        }

        // 回傳更新後的狀態
        return userQuotaRepository.findByUserId(userId)
            .map(QuotaStatusResponse::fromUserQuota)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    // ========== 額外額度管理 ==========

    /**
     * 給予用戶額外額度。
     *
     * @param userId 用戶 ID
     * @param request 額度給予請求
     * @param grantedBy 管理員（從 header 取得）
     * @return 更新後的配額狀態
     */
    @PostMapping("/users/{userId}/bonus")
    public ResponseEntity<QuotaStatusResponse> grantBonus(
            @PathVariable String userId,
            @RequestBody @Validated BonusGrantRequest request,
            @RequestHeader(value = "X-Admin-User", defaultValue = "system") String grantedBy) {

        log.info("Granting bonus to user: {} -> amount={}, reason={}, grantedBy={}",
            userId, request.amount(), request.reason(), grantedBy);

        try {
            UserQuota updatedQuota = bonusService.grantBonus(
                userId,
                request.amount(),
                request.reason(),
                grantedBy
            );

            return ResponseEntity.ok(QuotaStatusResponse.fromUserQuota(updatedQuota));
        } catch (BonusService.UserNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ========== 歷史查詢 ==========

    /**
     * 取得用戶歷史配額使用記錄。
     *
     * @param userId 用戶 ID
     * @param months 查詢月數（預設 6 個月）
     * @return 歷史記錄
     */
    @GetMapping("/users/{userId}/history")
    public ResponseEntity<QuotaHistoryResponse> getUserQuotaHistory(
            @PathVariable String userId,
            @RequestParam(defaultValue = "6") int months) {

        log.debug("Getting quota history for user: {}, months={}", userId, months);

        List<QuotaHistory> histories = quotaHistoryRepository
            .findByUserIdOrderByPeriodYearDescPeriodMonthDesc(userId, PageRequest.of(0, months))
            .getContent();

        return ResponseEntity.ok(QuotaHistoryResponse.fromHistoryList(userId, histories));
    }

    /**
     * 取得用戶額外額度記錄。
     *
     * @param userId 用戶 ID
     * @return 額外額度記錄
     */
    @GetMapping("/users/{userId}/bonus-history")
    public ResponseEntity<BonusHistoryResponse> getUserBonusHistory(@PathVariable String userId) {
        log.debug("Getting bonus history for user: {}", userId);

        List<BonusRecord> records = bonusService.getUserBonusHistory(userId);
        return ResponseEntity.ok(BonusHistoryResponse.fromRecordList(userId, records));
    }
}
