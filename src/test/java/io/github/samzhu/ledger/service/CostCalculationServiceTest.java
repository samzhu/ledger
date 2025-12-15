package io.github.samzhu.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.samzhu.ledger.config.LedgerProperties;
import io.github.samzhu.ledger.config.LedgerProperties.BatchConfig;
import io.github.samzhu.ledger.config.LedgerProperties.ModelPricing;
import io.github.samzhu.ledger.dto.UsageEvent;
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
        // Given: 1000 input tokens, 500 output tokens
        UsageEvent event = createEvent(
            "claude-sonnet-4-20250514",
            1000, 500, 0, 0, 1500
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
        // Given: 2000 input tokens, 1000 output tokens
        UsageEvent event = createEvent(
            "claude-opus-4-20250514",
            2000, 1000, 0, 0, 3000
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
        // Given: 1000 input (500 from cache read), 100 cache write, 500 output
        UsageEvent event = createEvent(
            "claude-sonnet-4-20250514",
            1000, 500, 100, 500, 1600
        );

        // When
        BigDecimal cost = costService.calculateCost(event);

        // Then
        // Billable input (1000 - 500 cache read): 500 * 3.00 / 1,000,000 = 0.0015
        // Cache read: 500 * 0.30 / 1,000,000 = 0.00015
        // Cache write: 100 * 3.75 / 1,000,000 = 0.000375
        // Output: 500 * 15.00 / 1,000,000 = 0.0075
        // Total: 0.009525
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.009525"));
    }

    @Test
    void shouldReturnZeroForUnknownModel() {
        // Given: unknown model
        UsageEvent event = createEvent(
            "unknown-model",
            1000, 500, 0, 0, 1500
        );

        // When
        BigDecimal cost = costService.calculateCost(event);

        // Then
        assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void shouldHandleZeroTokens() {
        // Given: zero tokens
        UsageEvent event = createEvent(
            "claude-sonnet-4-20250514",
            0, 0, 0, 0, 0
        );

        // When
        BigDecimal cost = costService.calculateCost(event);

        // Then
        assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private UsageEvent createEvent(String model, int inputTokens, int outputTokens,
                                   int cacheCreationTokens, int cacheReadTokens, int totalTokens) {
        UsageEventData data = new UsageEventData(
            model,
            "msg-1",
            inputTokens,
            outputTokens,
            cacheCreationTokens,
            cacheReadTokens,
            totalTokens,
            1000L,
            false,
            "end_turn",
            "success",
            null,
            "primary",
            "trace-1",
            "req-1"
        );
        return new UsageEvent("event-1", "user-1", LocalDate.now(), java.time.Instant.now(), data);
    }
}
