package com.banco.account_service.account;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyCodeTest {

    @Test
    void of_normalizesCaseAndWhitespace() {
        CurrencyCode currency = CurrencyCode.of("  eur ");

        assertThat(currency.code()).isEqualTo("EUR");
        assertThat(currency).hasToString("EUR");
    }

    @Test
    void of_whenBlank_throwsIllegalArgument() {
        assertThatThrownBy(() -> CurrencyCode.of("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency is required");
    }

    @Test
    void of_whenUnsupported_throwsIllegalArgument() {
        assertThatThrownBy(() -> CurrencyCode.of("XYZ"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported ISO 4217 currency");
    }

    @Test
    void constructor_whenNull_throwsNullPointer() {
        assertThatNullPointerException()
                .isThrownBy(() -> new CurrencyCode(null))
                .withMessageContaining("Currency is required");
    }

    @Test
    void fractionDigits_followsIso4217() {
        assertThat(CurrencyCode.of("EUR").fractionDigits()).isEqualTo(2);
        assertThat(CurrencyCode.of("JPY").fractionDigits()).isZero();
    }
}
