package io.github.samzhu.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.samzhu.ledger.config.LedgerProperties;
import io.github.samzhu.ledger.exception.UnknownModelPricingException;
import io.github.samzhu.ledger.config.LedgerProperties.BatchConfig;
import io.github.samzhu.ledger.config.LedgerProperties.ModelPricing;
import io.github.samzhu.ledger.dto.UsageEventData;

class CostCalculationServiceTest {

    private CostCalculationService costService;

    @BeforeEach
    void setUp() {
        // Setup pricing configuration
        Map<String, ModelPricing> pricing = Map.of(
            "claude-sonnet-4-20250514", new ModelPricing(
                new BigDecimal("3.00"),   // inputPerMillion
                new BigDecimal("15.00"),  // outputPerMillion
                new BigDecimal("0.30"),   // cacheReadPerMillion
                new BigDecimal("3.75")    // cacheWritePerMillion
            ),
            "claude-opus-4-20250514", new ModelPricing(
                new BigDecimal("15.00"),
                new BigDecimal("75.00"),
                new BigDecimal("1.50"),
                new BigDecimal("18.75")
            ),
            "claude-haiku-3-5-20241022", new ModelPricing(
                new BigDecimal("0.80"),
                new BigDecimal("4.00"),
                new BigDecimal("0.08"),
                new BigDecimal("1.00")
            )
        );

        LedgerProperties properties = new LedgerProperties(
            new BatchConfig(1000, "0 0,30 * * * *", "0 0 * * * *"),
            pricing,
            new LedgerProperties.LatencyConfig(100),
            new LedgerProperties.QuotaConfig(0, java.math.BigDecimal.ZERO, "MONTHLY")
        );

        costService = new CostCalculationService(properties);
    }

    @Test
    void shouldCalculateSonnetCost() {
        // Given: 1000 非快取輸入 tokens, 500 輸出 tokens
        UsageEventData event = createEvent(
            "claude-sonnet-4-20250514",
            1000, 500, 0, 0
        );

        // When
        BigDecimal cost = costService.calculateCost(event);

        // Then
        // Input: 1000 * 3.00 / 1,000,000 = 0.003
        // Output: 500 * 15.00 / 1,000,000 = 0.0075
        // Total: 0.0105
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.0105"));
    }

    @Test
    void shouldCalculateOpusCost() {
        // Given: 2000 非快取輸入 tokens, 1000 輸出 tokens
        UsageEventData event = createEvent(
            "claude-opus-4-20250514",
            2000, 1000, 0, 0
        );

        // When
        BigDecimal cost = costService.calculateCost(event);

        // Then
        // Input: 2000 * 15.00 / 1,000,000 = 0.03
        // Output: 1000 * 75.00 / 1,000,000 = 0.075
        // Total: 0.105
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.105"));
    }

    @Test
    void shouldCalculateCostWithCacheTokens() {
        // Given: 1000 非快取輸入, 500 快取讀取, 100 快取寫入, 500 輸出
        // 新語意: inputTokens 已經是非快取的輸入（快取斷點之後的部分）
        UsageEventData event = createEvent(
            "claude-sonnet-4-20250514",
            1000, 500, 100, 500
        );

        // When
        BigDecimal cost = costService.calculateCost(event);

        // Then
        // Input (非快取): 1000 * 3.00 / 1,000,000 = 0.003
        // Cache read: 500 * 0.30 / 1,000,000 = 0.00015
        // Cache write: 100 * 3.75 / 1,000,000 = 0.000375
        // Output: 500 * 15.00 / 1,000,000 = 0.0075
        // Total: 0.011025
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.011025"));
    }

    @Test
    void shouldThrowExceptionForUnknownModel() {
        // Given: unknown model (non-null but not configured)
        UsageEventData event = createEvent(
            "unknown-model",
            1000, 500, 0, 0
        );

        // When/Then: should throw UnknownModelPricingException
        assertThatThrownBy(() -> costService.calculateCost(event))
            .isInstanceOf(UnknownModelPricingException.class)
            .hasMessageContaining("unknown-model")
            .hasMessageContaining("trace-1");
    }

    @Test
    void shouldReturnZeroForNullModel() {
        // Given: null model (error event)
        UsageEventData event = createEvent(
            null,
            0, 0, 0, 0
        );

        // When
        BigDecimal cost = costService.calculateCost(event);

        // Then: null model is allowed (for error events), returns zero
        assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void shouldHandleZeroTokens() {
        // Given: zero tokens
        UsageEventData event = createEvent(
            "claude-sonnet-4-20250514",
            0, 0, 0, 0
        );

        // When
        BigDecimal cost = costService.calculateCost(event);

        // Then
        assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * 建立測試用 UsageEventData。
     *
     * @param model 模型名稱
     * @param inputTokens 非快取輸入 tokens（快取斷點之後的部分）
     * @param outputTokens 輸出 tokens
     * @param cacheCreationTokens 快取寫入 tokens
     * @param cacheReadTokens 快取讀取 tokens
     */
    private UsageEventData createEvent(String model, int inputTokens, int outputTokens,
                                   int cacheCreationTokens, int cacheReadTokens) {
        return new UsageEventData(
            "user-1",        // userId
            Instant.now(),   // eventTime
            model,           // model
            inputTokens,     // inputTokens (非快取輸入)
            outputTokens,    // outputTokens
            cacheCreationTokens, // cacheCreationTokens
            cacheReadTokens, // cacheReadTokens
            "msg-1",         // messageId
            1000L,           // latencyMs
            false,           // stream
            "end_turn",      // stopReason
            "success",       // status
            null,            // errorType
            "primary",       // keyAlias
            "trace-1",       // traceId
            "req-1"          // anthropicRequestId
        );
    }
}
