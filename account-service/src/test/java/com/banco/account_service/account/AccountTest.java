package com.banco.account_service.account;

import com.banco.account_service.account.exception.AccountNotActiveException;
import com.banco.account_service.account.exception.InsufficientFundsException;
import com.banco.account_service.account.exception.InvalidAmountScaleException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();

    @Test
    void open_initializesActiveAccountWithZeroBalance() {
        Account account = Account.open(CUSTOMER_ID, "ES9121000418450200051332", "eur");

        assertThat(account.getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(account.getNumber()).isEqualTo("ES9121000418450200051332");
        assertThat(account.getCurrency()).isEqualTo("EUR");
        assertThat(account.getCurrencyCode()).isEqualTo(CurrencyCode.of("EUR"));
        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getStatus()).isEqualTo(Status.ACTIVE);
    }

    @Test
    void open_whenNumberIsInvalid_throwsIllegalArgument() {
        assertThatThrownBy(() -> Account.open(CUSTOMER_ID, "   ", "EUR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid account number");

        assertThatThrownBy(() -> Account.open(CUSTOMER_ID, "x".repeat(35), "EUR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid account number");
    }

    @Test
    void credit_increasesBalance() {
        Account account = Account.open(CUSTOMER_ID, "CREDIT-001", "EUR");

        BigDecimal balance = account.credit(new BigDecimal("12.34"));

        assertThat(balance).isEqualByComparingTo("12.34");
        assertThat(account.getBalance()).isEqualByComparingTo("12.34");
    }

    @Test
    void debit_decreasesBalance() {
        Account account = Account.open(CUSTOMER_ID, "DEBIT-001", "EUR");
        account.credit(new BigDecimal("50.00"));

        BigDecimal balance = account.debit(new BigDecimal("20.50"));

        assertThat(balance).isEqualByComparingTo("29.50");
        assertThat(account.getBalance()).isEqualByComparingTo("29.50");
    }

    @Test
    void debit_withoutFunds_throwsInsufficientFundsAndDoesNotChangeBalance() {
        Account account = Account.open(CUSTOMER_ID, "DEBIT-002", "EUR");
        account.credit(new BigDecimal("10.00"));

        assertThatThrownBy(() -> account.debit(new BigDecimal("10.01")))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("requested 10.01, available 10.00");

        assertThat(account.getBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void movement_whenAmountIsNotPositive_throwsIllegalArgument() {
        Account account = Account.open(CUSTOMER_ID, "AMOUNT-001", "EUR");

        assertThatThrownBy(() -> account.credit(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> account.debit(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    void movement_whenScaleExceedsCurrency_throwsInvalidAmountScale() {
        Account account = Account.open(CUSTOMER_ID, "SCALE-001", "EUR");

        assertThatThrownBy(() -> account.credit(new BigDecimal("1.234")))
                .isInstanceOf(InvalidAmountScaleException.class)
                .hasMessageContaining("allows at most 2");
    }

    @Test
    void movement_allowsTrailingZerosBeyondCurrencyScale() {
        Account account = Account.open(CUSTOMER_ID, "SCALE-002", "EUR");

        assertThat(account.credit(new BigDecimal("1.2300")))
                .isEqualByComparingTo("1.2300");
    }

    @Test
    void movement_whenAccountIsNotActive_throwsAccountNotActive() {
        Account account = Account.open(CUSTOMER_ID, "STATUS-001", "EUR");
        ReflectionTestUtils.setField(account, "status", Status.BLOCKED);

        assertThatThrownBy(() -> account.credit(BigDecimal.ONE))
                .isInstanceOf(AccountNotActiveException.class)
                .hasMessageContaining("BLOCKED");
        assertThatThrownBy(() -> account.debit(BigDecimal.ONE))
                .isInstanceOf(AccountNotActiveException.class)
                .hasMessageContaining("BLOCKED");
    }
}
