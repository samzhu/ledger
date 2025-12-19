package io.github.samzhu.ledger.dto.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.github.samzhu.ledger.document.BonusRecord;
import io.github.samzhu.ledger.util.PeriodUtils;

/**
 * 額外額度歷史 API 回應。
 *
 * <p>用於 GET /api/v1/quota/users/{userId}/bonus-history 端點。
 */
public record BonusHistoryResponse(
    String userId,
    List<BonusItem> records
) {

    /**
     * 額外額度記錄項目。
     */
    public record BonusItem(
        String id,
        int periodYear,
        int periodMonth,
        String periodString,
        BigDecimal amount,
        String reason,
        String grantedBy,
        Instant createdAt
    ) {}

    /**
     * 從 BonusRecord 列表建立回應物件。
     */
    public static BonusHistoryResponse fromRecordList(String userId, List<BonusRecord> records) {
        List<BonusItem> items = records.stream()
            .map(r -> new BonusItem(
                r.id(),
                r.periodYear(),
                r.periodMonth(),
                PeriodUtils.formatPeriod(r.periodYear(), r.periodMonth()),
                r.amount(),
                r.reason(),
                r.grantedBy(),
                r.createdAt()
            ))
            .toList();

        return new BonusHistoryResponse(userId, items);
    }
}
