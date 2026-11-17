package com.banco.account_service.ledger.dto;

import com.banco.account_service.ledger.LedgerEntryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntryResponse(
        UUID id,
        UUID accountId,
        LedgerEntryType type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String reference,
        Instant createdAt
) {
}
