package com.banco.account_service.ledger.exception;

import com.banco.account_service.ledger.LedgerEntryType;
import lombok.Getter;

import java.util.UUID;

@Getter
public class DuplicateReferenceException extends RuntimeException {

    private final UUID accountId;
    private final String reference;
    private final LedgerEntryType type;

    public DuplicateReferenceException(UUID accountId, String reference, LedgerEntryType type) {
        super("Reference already used for account " + accountId
                + " and type " + type + ": " + reference);
        this.accountId = accountId;
        this.reference = reference;
        this.type = type;
    }
}
