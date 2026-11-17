package com.banco.account_service.account.dto;

import com.banco.account_service.account.Status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String number,
        String currency,
        BigDecimal balance,
        Status status,
        Instant createdAt
) {
}
