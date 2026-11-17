package com.banco.account_service.account.exception;

import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
public class InsufficientFundsException extends RuntimeException {

    private final UUID accountId;
    private final BigDecimal requested;
    private final BigDecimal available;

    public InsufficientFundsException(UUID accountId, BigDecimal requested, BigDecimal available) {
        super("Insufficient funds for account " + accountId
                + ": requested " + requested + ", available " + available);
        this.accountId = accountId;
        this.requested = requested;
        this.available = available;
    }
}
