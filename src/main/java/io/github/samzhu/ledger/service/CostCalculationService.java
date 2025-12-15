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
 * <p>計算公式：
 * <pre>
 * 成本 = (billableInput × inputPrice / 1M)
 *      + (cacheRead × cacheReadPrice / 1M)
 *      + (cacheWrite × cacheWritePrice / 1M)
 *      + (output × outputPrice / 1M)
 *
 * 其中 billableInput = inputTokens - cacheReadTokens
 * </pre>
 *
 * @see <a href="https://www.anthropic.com/pricing">Anthropic Pricing</a>
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
     * <p>若找不到對應模型的定價，會嘗試模糊比對（例如忽略版本號差異），
     * 仍找不到則回傳零成本並記錄警告。
     *
     * @param event 用量事件
     * @return 計算的成本（美元），精確到小數點後 6 位
     */
    public BigDecimal calculateCost(UsageEvent event) {
        ModelPricing pricing = findPricing(event.model());
        if (pricing == null) {
            log.warn("Unknown model pricing: {}, returning zero cost", event.model());
            return BigDecimal.ZERO;
        }

        // 計算可計費的輸入 token（扣除從 cache 讀取的部分）
        int billableInputTokens = event.inputTokens() - event.cacheReadTokens();
        BigDecimal inputCost = calculateTokenCost(billableInputTokens, pricing.inputPerMillion());

        // Cache 讀取成本（優惠價）
        BigDecimal cacheReadCost = calculateTokenCost(event.cacheReadTokens(), pricing.cacheReadPerMillion());

        // Cache 寫入成本
        BigDecimal cacheWriteCost = calculateTokenCost(event.cacheCreationTokens(), pricing.cacheWritePerMillion());

        // 輸出成本
        BigDecimal outputCost = calculateTokenCost(event.outputTokens(), pricing.outputPerMillion());

        BigDecimal totalCost = inputCost.add(cacheReadCost).add(cacheWriteCost).add(outputCost);

        log.debug("Cost calculated: model={}, input={}, output={}, cacheRead={}, cacheWrite={}, total=${}",
            event.model(), billableInputTokens, event.outputTokens(),
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
     */
    private BigDecimal calculateSingleEventCacheSavings(UsageEvent event) {
        ModelPricing pricing = findPricing(event.model());
        if (pricing == null || event.cacheReadTokens() <= 0) {
            return BigDecimal.ZERO;
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
     */
    public CostBreakdown calculateCostBreakdown(List<UsageEvent> events) {
        BigDecimal inputCost = BigDecimal.ZERO;
        BigDecimal outputCost = BigDecimal.ZERO;
        BigDecimal cacheReadCost = BigDecimal.ZERO;
        BigDecimal cacheWriteCost = BigDecimal.ZERO;

        for (UsageEvent event : events) {
            ModelPricing pricing = findPricing(event.model());
            if (pricing == null) {
                continue;
            }

            // Billable input = input - cacheRead
            int billableInput = event.inputTokens() - event.cacheReadTokens();

            inputCost = inputCost.add(
                BigDecimal.valueOf(billableInput)
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
     */
    private ModelPricing findPricing(String model) {
        if (properties.pricing() == null) {
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
