package io.github.samzhu.ledger.dto.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 配額設定請求。
 *
 * <p>用於 PUT /api/v1/quota/users/{userId}/config 端點。
 */
public record QuotaConfigRequest(
    @NotNull(message = "enabled is required")
    Boolean enabled,

    @NotNull(message = "costLimitUsd is required")
    @PositiveOrZero(message = "costLimitUsd must be positive or zero")
    BigDecimal costLimitUsd
) {}
