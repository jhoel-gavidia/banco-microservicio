package com.banco.transfer_service.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Money {

    private static final int MAX_INTEGER_DIGITS = 15;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    private Money(BigDecimal amount, String currency) {
        Objects.requireNonNull(amount, "Amount is required");

        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }

        String code = currency.trim().toUpperCase(Locale.ROOT);
        Currency isoCurrency;
        try {
            isoCurrency = Currency.getInstance(code);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported ISO 4217 currency: " + currency, ex);
        }

        int maxFractionDigits = Math.max(isoCurrency.getDefaultFractionDigits(), 0);
        int actualFractionDigits = Math.max(amount.stripTrailingZeros().scale(), 0);
        if (actualFractionDigits > maxFractionDigits) {
            throw new IllegalArgumentException(
                    "Amount has too many decimal places for " + code
            );
        }

        int integerDigits = Math.max(amount.precision() - amount.scale(), 1);
        if (integerDigits > MAX_INTEGER_DIGITS) {
            throw new IllegalArgumentException("Amount is too large");
        }

        this.amount = amount;
        this.currency = code;
    }

    public static Money of(BigDecimal amount, String currency) {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency is required");
        }
        return new Money(amount, currency);
    }

    @Override
    public String toString() {
        return amount + " " + currency;
    }
}
