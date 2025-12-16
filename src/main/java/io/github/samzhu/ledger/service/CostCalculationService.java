package io.github.samzhu.ledger.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.samzhu.ledger.config.LedgerProperties;
import io.github.samzhu.ledger.config.LedgerProperties.ModelPricing;
import io.github.samzhu.ledger.document.DailyUserUsage.CostBreakdown;
import io.github.samzhu.ledger.dto.UsageEvent;
import io.github.samzhu.ledger.exception.UnknownModelPricingException;

/**
 * LLM API 用量成本計算服務。
 *
 * <p>根據 Anthropic 的定價結構計算每次 API 呼叫的成本，支援：
 * <ul>
 *   <li>不同模型的差異定價（Sonnet、Opus、Haiku 等）</li>
 *   <li>輸入/輸出 token 分別計價</li>
 *   <li>Prompt Cache 讀取/寫入的優惠價格</li>
 * </ul>
 *
 * <h3>Token 定義（依據 Anthropic API）</h3>
 * <ul>
 *   <li>{@code inputTokens} - 快取斷點之後的 tokens（非快取輸入，以 base rate 計費）</li>
 *   <li>{@code cacheReadTokens} - 從快取讀取的 tokens（以 0.1× base rate 計費）</li>
 *   <li>{@code cacheCreationTokens} - 寫入快取的 tokens（以 1.25× base rate 計費）</li>
 * </ul>
 *
 * <p>計算公式：
 * <pre>
 * 成本 = (inputTokens × inputPrice / 1M)
 *      + (cacheReadTokens × cacheReadPrice / 1M)
 *      + (cacheCreationTokens × cacheWritePrice / 1M)
 *      + (outputTokens × outputPrice / 1M)
 * </pre>
 *
 * @see <a href="https://platform.claude.com/docs/en/about-claude/pricing">Anthropic Pricing</a>
 * @see <a href="https://platform.claude.com/docs/en/build-with-claude/prompt-caching">Prompt Caching</a>
 */
@Service
public class CostCalculationService {

    private static final Logger log = LoggerFactory.getLogger(CostCalculationService.class);
    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    private final LedgerProperties properties;

    public CostCalculationService(LedgerProperties properties) {
        this.properties = properties;
        log.info("CostCalculationService initialized with {} model pricing configurations",
            properties.pricing() != null ? properties.pricing().size() : 0);
    }

    /**
     * 計算單筆用量事件的成本。
     *
     * <p>若找不到對應模型的定價，會嘗試模糊比對（例如忽略版本號差異）。
     *
     * <p>行為說明：
     * <ul>
     *   <li>model = null（error 事件）：回傳零成本，這是正常情況</li>
     *   <li>model = "xxx" 但找不到定價：拋出 {@link UnknownModelPricingException}</li>
     * </ul>
     *
     * <p>根據 Anthropic API，{@code inputTokens} 已經只包含非快取的輸入 tokens，
     * 不需要再減去 {@code cacheReadTokens}。
     *
     * @param event 用量事件
     * @return 計算的成本（美元），精確到小數點後 6 位
     * @throws UnknownModelPricingException 若模型非 null 但找不到定價配置
     */
    public BigDecimal calculateCost(UsageEvent event) {
        // Error 事件可能沒有 model，這是允許的
        if (event.model() == null) {
            log.debug("Event has null model (likely error event), returning zero cost: eventId={}",
                event.eventId());
            return BigDecimal.ZERO;
        }

        ModelPricing pricing = findPricing(event.model());
        if (pricing == null) {
            log.error("Unknown model pricing detected: model='{}', eventId='{}'. " +
                "Please add pricing configuration in application.yaml",
                event.model(), event.eventId());
            throw new UnknownModelPricingException(event.model(), event.eventId());
        }

        // inputTokens 已經是非快取的輸入 tokens（快取斷點之後的部分）
        BigDecimal inputCost = calculateTokenCost(event.inputTokens(), pricing.inputPerMillion());

        // Cache 讀取成本（0.1× base rate）
        BigDecimal cacheReadCost = calculateTokenCost(event.cacheReadTokens(), pricing.cacheReadPerMillion());

        // Cache 寫入成本（1.25× base rate）
        BigDecimal cacheWriteCost = calculateTokenCost(event.cacheCreationTokens(), pricing.cacheWritePerMillion());

        // 輸出成本
        BigDecimal outputCost = calculateTokenCost(event.outputTokens(), pricing.outputPerMillion());

        BigDecimal totalCost = inputCost.add(cacheReadCost).add(cacheWriteCost).add(outputCost);

        log.debug("Cost calculated: model={}, input={}, output={}, cacheRead={}, cacheWrite={}, total=${}",
            event.model(), event.inputTokens(), event.outputTokens(),
            event.cacheReadTokens(), event.cacheCreationTokens(), totalCost);

        return totalCost;
    }

    /**
     * 計算指定 token 數量的成本。
     */
    private BigDecimal calculateTokenCost(int tokens, BigDecimal pricePerMillion) {
        if (tokens <= 0 || pricePerMillion == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(tokens)
            .multiply(pricePerMillion)
            .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
    }

    /**
     * 計算批次事件的總成本。
     *
     * @param events 用量事件列表
     * @return 批次總成本（美元）
     */
    public BigDecimal calculateBatchCost(List<UsageEvent> events) {
        return events.stream()
            .map(this::calculateCost)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 計算 Cache 節省的成本。
     *
     * <p>計算如果沒有 Prompt Cache，需要多付的成本。
     * 節省金額 = (cacheReadTokens × inputPrice) - (cacheReadTokens × cacheReadPrice)
     *
     * @param events 用量事件列表
     * @return 因 Cache 節省的成本（美元）
     */
    public BigDecimal calculateCacheSavings(List<UsageEvent> events) {
        return events.stream()
            .map(this::calculateSingleEventCacheSavings)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 計算單筆事件的 Cache 節省成本。
     *
     * @throws UnknownModelPricingException 若模型非 null 但找不到定價配置
     */
    private BigDecimal calculateSingleEventCacheSavings(UsageEvent event) {
        // Error 事件可能沒有 model，這是允許的
        if (event.model() == null || event.cacheReadTokens() <= 0) {
            return BigDecimal.ZERO;
        }

        ModelPricing pricing = findPricing(event.model());
        if (pricing == null) {
            throw new UnknownModelPricingException(event.model(), event.eventId());
        }

        // 如果沒有 cache，需要付的 input 成本
        BigDecimal fullInputCost = BigDecimal.valueOf(event.cacheReadTokens())
            .multiply(pricing.inputPerMillion())
            .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);

        // 實際 cache read 成本
        BigDecimal cacheReadCost = BigDecimal.valueOf(event.cacheReadTokens())
            .multiply(pricing.cacheReadPerMillion())
            .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);

        return fullInputCost.subtract(cacheReadCost);
    }

    /**
     * 計算成本細分。
     *
     * <p>將總成本拆分為各類別：輸入、輸出、Cache 讀取、Cache 寫入。
     *
     * @param events 用量事件列表
     * @return 成本細分
     * @throws UnknownModelPricingException 若有事件的模型非 null 但找不到定價配置
     */
    public CostBreakdown calculateCostBreakdown(List<UsageEvent> events) {
        BigDecimal inputCost = BigDecimal.ZERO;
        BigDecimal outputCost = BigDecimal.ZERO;
        BigDecimal cacheReadCost = BigDecimal.ZERO;
        BigDecimal cacheWriteCost = BigDecimal.ZERO;

        for (UsageEvent event : events) {
            // Error 事件可能沒有 model，跳過
            if (event.model() == null) {
                continue;
            }

            ModelPricing pricing = findPricing(event.model());
            if (pricing == null) {
                throw new UnknownModelPricingException(event.model(), event.eventId());
            }

            // inputTokens 已經是非快取的輸入（快取斷點之後的部分）
            inputCost = inputCost.add(
                BigDecimal.valueOf(event.inputTokens())
                    .multiply(pricing.inputPerMillion())
                    .divide(ONE_MILLION, 6, RoundingMode.HALF_UP));

            outputCost = outputCost.add(
                BigDecimal.valueOf(event.outputTokens())
                    .multiply(pricing.outputPerMillion())
                    .divide(ONE_MILLION, 6, RoundingMode.HALF_UP));

            cacheReadCost = cacheReadCost.add(
                BigDecimal.valueOf(event.cacheReadTokens())
                    .multiply(pricing.cacheReadPerMillion())
                    .divide(ONE_MILLION, 6, RoundingMode.HALF_UP));

            cacheWriteCost = cacheWriteCost.add(
                BigDecimal.valueOf(event.cacheCreationTokens())
                    .multiply(pricing.cacheWritePerMillion())
                    .divide(ONE_MILLION, 6, RoundingMode.HALF_UP));
        }

        return new CostBreakdown(inputCost, outputCost, cacheReadCost, cacheWriteCost);
    }

    /**
     * 查找模型定價，支援完全比對和模糊比對。
     *
     * @param model 模型名稱，可能為 null（例如 error 事件）
     * @return 模型定價，若 model 為 null 或找不到定價則回傳 null
     */
    private ModelPricing findPricing(String model) {
        // 處理 error 事件可能沒有 model 欄位的情況
        if (model == null || properties.pricing() == null) {
            return null;
        }

        // 完全比對
        if (properties.pricing().containsKey(model)) {
            return properties.pricing().get(model);
        }

        // 模糊比對（處理版本號變化，例如 claude-sonnet-4-20250514 vs claude-sonnet-4-20250601）
        for (var entry : properties.pricing().entrySet()) {
            String key = entry.getKey();
            int matchLength = Math.min(15, key.length());
            if (model.startsWith(key.substring(0, matchLength))) {
                log.debug("Fuzzy matched pricing: {} -> {}", model, key);
                return entry.getValue();
            }
        }

        return null;
    }
}
