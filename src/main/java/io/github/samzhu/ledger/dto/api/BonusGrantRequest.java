package io.github.samzhu.ledger.dto.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 額外額度給予請求。
 *
 * <p>用於 POST /api/v1/quota/users/{userId}/bonus 端點。
 */
public record BonusGrantRequest(
    @NotNull(message = "amount is required")
    @Positive(message = "amount must be positive")
    BigDecimal amount,

    @NotBlank(message = "reason is required")
    String reason
) {}
