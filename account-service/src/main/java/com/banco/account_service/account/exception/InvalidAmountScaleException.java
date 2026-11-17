package com.banco.account_service.account.exception;

import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class InvalidAmountScaleException extends RuntimeException {

    private final BigDecimal amount;
    private final String currency;
    private final int maxFractionDigits;
    private final int actualFractionDigits;

    public InvalidAmountScaleException(
            BigDecimal amount,
            String currency,
            int maxFractionDigits,
            int actualFractionDigits
    ) {
        super("Amount " + amount.toPlainString() + " has " + actualFractionDigits
                + " decimal places but " + currency + " allows at most " + maxFractionDigits);
        this.amount = amount;
        this.currency = currency;
        this.maxFractionDigits = maxFractionDigits;
        this.actualFractionDigits = actualFractionDigits;
    }
}