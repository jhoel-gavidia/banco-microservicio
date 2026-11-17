package com.banco.account_service.account;

import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

public record CurrencyCode(String code) {

    public CurrencyCode {
        Objects.requireNonNull(code, "Currency is required");

        String normalized = code.trim().toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported ISO 4217 currency: " + code, ex);
        }
        code = normalized;
    }

    public static CurrencyCode of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Currency is required");
        }
        return new CurrencyCode(value);
    }

    public int fractionDigits() {
        return Currency.getInstance(code).getDefaultFractionDigits();
    }

    @Override
    public String toString() {
        return code;
    }
}
