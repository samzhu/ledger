package io.github.samzhu.ledger.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.samzhu.ledger.document.BonusRecord;
import io.github.samzhu.ledger.document.UserQuota;
import io.github.samzhu.ledger.repository.BonusRecordRepository;
import io.github.samzhu.ledger.repository.UserQuotaRepository;
import io.github.samzhu.ledger.util.PeriodUtils;

/**
 * 額外額度管理服務。
 *
 * <p>提供管理員給予用戶額外配額的功能。
 * 簡化設計，無審核流程，管理員直接給予。
 */
@Service
public class BonusService {

    private static final Logger log = LoggerFactory.getLogger(BonusService.class);

    private final BonusRecordRepository bonusRecordRepository;
    private final UserQuotaRepository userQuotaRepository;

    public BonusService(
            BonusRecordRepository bonusRecordRepository,
            UserQuotaRepository userQuotaRepository) {
        this.bonusRecordRepository = bonusRecordRepository;
        this.userQuotaRepository = userQuotaRepository;
    }

    /**
     * 給予用戶額外額度。
     *
     * @param userId 用戶 ID
     * @param amount 額度金額 (USD)
     * @param reason 給予原因
     * @param grantedBy 管理員
     * @return 更新後的 UserQuota
     * @throws UserNotFoundException 如果用戶不存在
     */
    @Transactional
    public UserQuota grantBonus(
            String userId,
            BigDecimal amount,
            String reason,
            String grantedBy) {

        int currentYear = PeriodUtils.getCurrentYear();
        int currentMonth = PeriodUtils.getCurrentMonth();
        Instant now = Instant.now();

        // 1. 查詢現有配額
        UserQuota quota = userQuotaRepository.findByUserId(userId)
            .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

        // 2. 建立記錄
        BonusRecord record = BonusRecord.create(
            userId,
            currentYear,
            currentMonth,
            amount,
            reason,
            grantedBy
        );
        bonusRecordRepository.save(record);
        log.info("Created bonus record: userId={}, amount={}, grantedBy={}", userId, amount, grantedBy);

        // 3. 累加額外額度 (BigDecimal → double for storage)
        userQuotaRepository.addBonusByUserId(userId, amount.doubleValue(), reason, now);

        // 4. 重算使用率 (計算使用 BigDecimal，儲存使用 double)
        BigDecimal currentBonus = BigDecimal.valueOf(quota.bonusCostUsd());
        BigDecimal newBonus = currentBonus.add(amount);
        BigDecimal effectiveLimit = BigDecimal.valueOf(quota.costLimitUsd()).add(newBonus);
        BigDecimal periodCost = BigDecimal.valueOf(quota.periodCostUsd());

        double usagePercent = UserQuota.calculateCostUsagePercent(periodCost.doubleValue(), effectiveLimit.doubleValue());
        boolean exceeded = usagePercent >= 100;

        userQuotaRepository.updateQuotaStatusByUserId(userId, usagePercent, exceeded, now);
        log.info("Updated quota status after bonus: userId={}, usagePercent={}", userId, usagePercent);

        return userQuotaRepository.findByUserId(userId).orElse(quota);
    }

    /**
     * 查詢用戶的額外額度記錄。
     *
     * @param userId 用戶 ID
     * @return 額外額度記錄清單
     */
    public List<BonusRecord> getUserBonusHistory(String userId) {
        return bonusRecordRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 查詢用戶特定月份的額外額度記錄。
     *
     * @param userId 用戶 ID
     * @param year 年份
     * @param month 月份
     * @return 該月額外額度記錄清單
     */
    public List<BonusRecord> getUserBonusHistoryForPeriod(String userId, int year, int month) {
        return bonusRecordRepository.findByUserIdAndPeriodYearAndPeriodMonth(userId, year, month);
    }

    /**
     * 用戶不存在例外。
     */
    public static class UserNotFoundException extends RuntimeException {
        public UserNotFoundException(String message) {
            super(message);
        }
    }
}
