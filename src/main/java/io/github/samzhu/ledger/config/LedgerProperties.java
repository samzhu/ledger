package io.github.samzhu.ledger.config;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ledger 服務的組態屬性，支援型別安全的配置綁定。
 *
 * <p>此配置包含以下部分：
 * <ul>
 *   <li>{@link BatchConfig} - 事件批次處理設定，控制緩衝區大小和刷新間隔</li>
 *   <li>{@link ModelPricing} - 各 LLM 模型的 token 定價，用於成本計算</li>
 *   <li>{@link LatencyConfig} - 延遲百分位計算設定 (T-Digest)</li>
 *   <li>{@link QuotaConfig} - 用戶配額預設設定</li>
 * </ul>
 *
 * <p>配置範例 (application.yaml)：
 * <pre>
 * ledger:
 *   batch:
 *     size: 100
 *     interval-ms: 5000
 *   latency:
 *     digest-compression: 100
 *   quota:
 *     default-token-limit: 0
 *     default-cost-limit-usd: 0
 *     default-period: MONTHLY
 *   pricing:
 *     claude-sonnet-4-20250514:
 *       input-per-million: 3.00
 *       output-per-million: 15.00
 *       cache-read-per-million: 0.30
 *       cache-write-per-million: 3.75
 * </pre>
 *
 * @see <a href="https://docs.spring.io/spring-boot/reference/features/external-config.html">Spring Boot Externalized Configuration</a>
 */
@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(
    BatchConfig batch,
    Map<String, ModelPricing> pricing,
    LatencyConfig latency,
    QuotaConfig quota
) {
    /**
     * 事件批次處理設定。
     *
     * <p>控制 {@link io.github.samzhu.ledger.service.EventBufferService} 的行為：
     * <ul>
     *   <li>當緩衝區事件數達到 {@code size} 時立即觸發寫入</li>
     *   <li>依 {@code flushCron} 定時觸發寫入（預設每小時 00 分和 30 分）</li>
     *   <li>依 {@code settlementCron} 定時觸發結算（預設每小時整點）</li>
     * </ul>
     *
     * <p>這種設計平衡了寫入效率和資料即時性。
     *
     * @param size 批次大小，預設 1000
     * @param flushCron 定時刷新 Cron 表達式，預設每小時 00 分和 30 分
     * @param settlementCron 定時結算 Cron 表達式，預設每小時整點
     */
    public record BatchConfig(
        int size,
        String flushCron,
        String settlementCron
    ) {
        public BatchConfig {
            if (size <= 0) {
                size = 1000;
            }
            if (flushCron == null || flushCron.isBlank()) {
                flushCron = "0 0,30 * * * *";
            }
            if (settlementCron == null || settlementCron.isBlank()) {
                settlementCron = "0 0 * * * *";
            }
        }

        /**
         * 建立預設批次設定。
         */
        public static BatchConfig defaults() {
            return new BatchConfig(1000, "0 0,30 * * * *", "0 0 * * * *");
        }
    }

    /**
     * LLM 模型的 token 定價設定。
     *
     * <p>價格單位為「美元 / 百萬 tokens」，與 Anthropic 官方定價一致。
     * 成本計算公式：
     * <pre>
     * 總成本 = (inputTokens × inputPerMillion / 1,000,000)
     *        + (outputTokens × outputPerMillion / 1,000,000)
     *        + (cacheReadTokens × cacheReadPerMillion / 1,000,000)
     *        + (cacheCreationTokens × cacheWritePerMillion / 1,000,000)
     * </pre>
     *
     * @param inputPerMillion 輸入 token 單價 (USD/百萬)
     * @param outputPerMillion 輸出 token 單價 (USD/百萬)
     * @param cacheReadPerMillion Prompt Cache 讀取單價 (USD/百萬)
     * @param cacheWritePerMillion Prompt Cache 寫入單價 (USD/百萬)
     * @see <a href="https://www.anthropic.com/pricing">Anthropic Pricing</a>
     */
    public record ModelPricing(
        BigDecimal inputPerMillion,
        BigDecimal outputPerMillion,
        BigDecimal cacheReadPerMillion,
        BigDecimal cacheWritePerMillion
    ) {}

    /**
     * 延遲百分位計算設定。
     *
     * <p>使用 T-Digest 演算法計算延遲的百分位數 (P50/P90/P95/P99)。
     * 壓縮因子控制精度和記憶體使用的平衡。
     *
     * @param digestCompression T-Digest 壓縮因子，預設 100，範圍 50-200
     *                          較高的值提供更高精度但使用更多記憶體
     */
    public record LatencyConfig(
        int digestCompression
    ) {
        public LatencyConfig {
            if (digestCompression <= 0) {
                digestCompression = 100;
            }
        }

        /**
         * 建立預設延遲設定。
         */
        public static LatencyConfig defaults() {
            return new LatencyConfig(100);
        }
    }

    /**
     * 用戶配額預設設定。
     *
     * <p>定義新用戶的預設配額限制。配額可按週期 (日/週/月) 重置。
     *
     * @param defaultTokenLimit 預設 Token 限制，0 表示無限制
     * @param defaultCostLimitUsd 預設成本限制 (USD)，0 表示無限制
     * @param defaultPeriod 預設配額週期，可為 DAILY、WEEKLY、MONTHLY
     */
    public record QuotaConfig(
        long defaultTokenLimit,
        BigDecimal defaultCostLimitUsd,
        String defaultPeriod
    ) {
        public QuotaConfig {
            if (defaultTokenLimit < 0) {
                defaultTokenLimit = 0;
            }
            if (defaultCostLimitUsd == null) {
                defaultCostLimitUsd = BigDecimal.ZERO;
            }
            if (defaultPeriod == null || defaultPeriod.isBlank()) {
                defaultPeriod = "MONTHLY";
            }
        }

        /**
         * 建立預設配額設定 (無限制)。
         */
        public static QuotaConfig defaults() {
            return new QuotaConfig(0, BigDecimal.ZERO, "MONTHLY");
        }
    }
}
