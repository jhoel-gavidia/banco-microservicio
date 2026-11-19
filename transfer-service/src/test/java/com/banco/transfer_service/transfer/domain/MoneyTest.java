package com.banco.transfer_service.transfer.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void of_normalizesCurrencyAndKeepsAmount() {
        Money money = Money.of(new BigDecimal("12.30"), " eur ");

        assertThat(money.getAmount()).isEqualByComparingTo("12.30");
        assertThat(money.getCurrency()).isEqualTo("EUR");
        assertThat(money).hasToString("12.30 EUR");
    }

    @Test
    void of_whenCurrencyIsBlank_throwsIllegalArgument() {
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency is required");
    }

    @Test
    void of_whenCurrencyIsUnsupported_throwsIllegalArgument() {
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "XYZ"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported ISO 4217 currency");
    }

    @Test
    void of_whenAmountIsNotPositive_throwsIllegalArgument() {
        assertThatThrownBy(() -> Money.of(BigDecimal.ZERO, "EUR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    void of_whenScaleExceedsCurrency_throwsIllegalArgument() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("1.234"), "EUR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many decimal places");
    }

    @Test
    void of_allowsZeroFractionCurrency() {
        assertThat(Money.of(new BigDecimal("100"), "JPY").getCurrency()).isEqualTo("JPY");
    }

    @Test
    void of_whenAmountIsTooLarge_throwsIllegalArgument() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("1".repeat(16)), "EUR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }
}
