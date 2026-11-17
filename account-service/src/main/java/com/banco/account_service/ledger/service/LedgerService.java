package com.banco.account_service.ledger.service;

import com.banco.account_service.ledger.dto.LedgerEntryResponse;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface LedgerService {

    LedgerEntryResponse deposit(UUID accountId, BigDecimal amount, String reference);

    LedgerEntryResponse withdraw(UUID accountId, BigDecimal amount, String reference);

    List<LedgerEntryResponse> getMovements(UUID accountId);
}
