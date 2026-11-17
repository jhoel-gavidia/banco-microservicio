package com.banco.account_service.ledger.service;

import com.banco.account_service.ledger.dto.LedgerEntryResponse;
import com.banco.account_service.security.CustomerContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface LedgerService {

    LedgerEntryResponse deposit(
            UUID accountId,
            BigDecimal amount,
            String reference,
            String idempotencyKey,
            CustomerContext customer
    );

    LedgerEntryResponse withdraw(
            UUID accountId,
            BigDecimal amount,
            String reference,
            String idempotencyKey,
            CustomerContext customer
    );

    List<LedgerEntryResponse> getMovements(UUID accountId, CustomerContext customer);
}
