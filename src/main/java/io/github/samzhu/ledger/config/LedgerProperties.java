package io.github.samzhu.ledger.config;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ledger 服務的組態屬性，支援型別安全的配置綁定。
 *
 * <p>此配置包含兩個主要部分：
 * <ul>
 *   <li>{@link BatchConfig} - 事件批次處理設定，控制緩衝區大小和刷新間隔</li>
 *   <li>{@link ModelPricing} - 各 LLM 模型的 token 定價，用於成本計算</li>
 * </ul>
 *
 * <p>配置範例 (application.yaml)：
 * <pre>
 * ledger:
 *   batch:
 *     size: 100           # 批次大小，達到此數量觸發寫入
 *     interval-ms: 5000   # 定時刷新間隔 (毫秒)
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
    Map<String, ModelPricing> pricing
) {
    /**
     * 事件批次處理設定。
     *
     * <p>控制 {@link io.github.samzhu.ledger.service.EventBufferService} 的行為：
     * <ul>
     *   <li>當緩衝區事件數達到 {@code size} 時立即觸發寫入</li>
     *   <li>即使未達數量，每隔 {@code intervalMs} 毫秒也會觸發寫入</li>
     * </ul>
     *
     * <p>這種設計平衡了寫入效率和資料即時性。
     *
     * @param size 批次大小，建議 50-200
     * @param intervalMs 定時刷新間隔 (毫秒)，建議 3000-10000
     */
    public record BatchConfig(
        int size,
        long intervalMs
    ) {}

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
}
