package io.github.samzhu.ledger.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * 週期管理工具類。
 *
 * <p>提供配額週期相關的計算和比較方法。
 * 所有時間計算均使用 UTC 時區。
 */
public final class PeriodUtils {

    private PeriodUtils() {
        // 工具類不允許實例化
    }

    /**
     * 取得當前年份 (UTC)。
     *
     * @return 當前年份
     */
    public static int getCurrentYear() {
        return LocalDate.now(ZoneOffset.UTC).getYear();
    }

    /**
     * 取得當前月份 (UTC)。
     *
     * @return 當前月份 (1-12)
     */
    public static int getCurrentMonth() {
        return LocalDate.now(ZoneOffset.UTC).getMonthValue();
    }

    /**
     * 取得週期開始時間。
     *
     * @param year 年份
     * @param month 月份 (1-12)
     * @return 該月 1 號 00:00:00 UTC
     */
    public static Instant getPeriodStart(int year, int month) {
        return LocalDate.of(year, month, 1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant();
    }

    /**
     * 取得週期結束時間。
     *
     * @param year 年份
     * @param month 月份 (1-12)
     * @return 該月最後一天 23:59:59.999 UTC
     */
    public static Instant getPeriodEnd(int year, int month) {
        LocalDate firstDay = LocalDate.of(year, month, 1);
        LocalDate lastDay = firstDay.withDayOfMonth(firstDay.lengthOfMonth());
        return lastDay.atTime(23, 59, 59, 999_000_000)
            .atZone(ZoneOffset.UTC)
            .toInstant();
    }

    /**
     * 檢查兩個週期是否相同。
     *
     * @param year1 第一個週期的年份
     * @param month1 第一個週期的月份
     * @param year2 第二個週期的年份
     * @param month2 第二個週期的月份
     * @return true 表示相同週期
     */
    public static boolean isSamePeriod(int year1, int month1, int year2, int month2) {
        return year1 == year2 && month1 == month2;
    }

    /**
     * 檢查指定週期是否為當前週期。
     *
     * @param year 年份
     * @param month 月份
     * @return true 表示是當前週期
     */
    public static boolean isCurrentPeriod(int year, int month) {
        return isSamePeriod(year, month, getCurrentYear(), getCurrentMonth());
    }

    /**
     * 計算距離週期結束的剩餘天數。
     *
     * @param periodEndAt 週期結束時間
     * @return 剩餘天數，如果已過期則返回 0
     */
    public static long getDaysRemaining(Instant periodEndAt) {
        if (periodEndAt == null) {
            return 0;
        }
        Instant now = Instant.now();
        if (now.isAfter(periodEndAt)) {
            return 0;
        }
        return ChronoUnit.DAYS.between(now, periodEndAt);
    }

    /**
     * 取得週期的格式化字串。
     *
     * @param year 年份
     * @param month 月份
     * @return 格式如 "2025-12"
     */
    public static String formatPeriod(int year, int month) {
        return String.format("%d-%02d", year, month);
    }

    /**
     * 取得前一個週期的年份。
     *
     * @param year 當前年份
     * @param month 當前月份
     * @return 前一個週期的年份
     */
    public static int getPreviousYear(int year, int month) {
        return month == 1 ? year - 1 : year;
    }

    /**
     * 取得前一個週期的月份。
     *
     * @param month 當前月份
     * @return 前一個週期的月份 (1-12)
     */
    public static int getPreviousMonth(int month) {
        return month == 1 ? 12 : month - 1;
    }
}
