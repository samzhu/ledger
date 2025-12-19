package io.github.samzhu.ledger.document;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 額外額度記錄文件。
 *
 * <p>用途：記錄管理員給予用戶額外額度的操作，供稽核追蹤。
 * 簡化設計，無審核流程，管理員直接給予。
 *
 * <p>設計原則：
 * <ul>
 *   <li>ID 自動生成：由 MongoDB 自動產生 ObjectId</li>
 *   <li>只增不改：記錄一旦建立就不會修改</li>
 *   <li>稽核追蹤：記錄誰在何時給予了多少額度</li>
 * </ul>
 */
@Document(collection = "bonus_records")
@CompoundIndex(name = "user_period_idx", def = "{'userId': 1, 'periodYear': -1, 'periodMonth': -1}")
public record BonusRecord(
    @Id String id,

    // ========== 基本識別 ==========
    /** 接收額外額度的用戶 ID */
    String userId,

    // ========== 週期標識 ==========
    /** 給予額度的年份 */
    int periodYear,
    /** 給予額度的月份 (1-12) */
    int periodMonth,

    // ========== 額度資訊 ==========
    /** 給予的額度金額 (USD) */
    BigDecimal amount,
    /** 給予原因，供稽核查閱 */
    String reason,
    /** 執行給予操作的管理員 ID 或名稱 */
    String grantedBy,

    // ========== 時間戳記 ==========
    /** 記錄建立時間 */
    Instant createdAt
) {

    /**
     * 建立額外額度記錄。
     *
     * @param userId 用戶 ID
     * @param periodYear 年份
     * @param periodMonth 月份
     * @param amount 額度金額
     * @param reason 給予原因
     * @param grantedBy 管理員
     * @return BonusRecord
     */
    public static BonusRecord create(
            String userId,
            int periodYear,
            int periodMonth,
            BigDecimal amount,
            String reason,
            String grantedBy) {

        return new BonusRecord(
            null, // ID 自動產生
            userId,
            periodYear,
            periodMonth,
            amount,
            reason,
            grantedBy,
            Instant.now()
        );
    }

    /**
     * 取得週期的格式化字串。
     *
     * @return 格式如 "2025-12"
     */
    public String getPeriodString() {
        return String.format("%d-%02d", periodYear, periodMonth);
    }
}
