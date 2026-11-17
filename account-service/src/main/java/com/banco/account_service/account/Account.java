package com.banco.account_service.account;

import com.banco.account_service.account.exception.InsufficientFundsException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "number", nullable = false, length = 34)
    private String number;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @org.hibernate.annotations.Generated(
            event = org.hibernate.generator.EventType.INSERT
    )
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false,
            insertable = false
    )
    private Instant createdAt;

    public static Account open(
            UUID customerId,
            String number,
            String currency
    ) {
        Objects.requireNonNull(customerId, "Customer ID is required");

        if (number == null || number.isBlank() || number.length() > 34) {
            throw new IllegalArgumentException("Invalid account number");
        }

        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Invalid currency");
        }

        Account account = new Account();

        account.customerId = customerId;
        account.number = number;
        account.currency = currency;
        account.balance = BigDecimal.ZERO;
        account.status = Status.ACTIVE;

        return account;
    }

    public BigDecimal credit(BigDecimal amount) {
        validateAmount(amount);
        balance = balance.add(amount);
        return balance;
    }

    public BigDecimal debit(BigDecimal amount) {
        validateAmount(amount);

        BigDecimal remaining = balance.subtract(amount);
        if (remaining.signum() < 0) {
            throw new InsufficientFundsException(id, amount, balance);
        }

        balance = remaining;
        return balance;
    }

    private static void validateAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "Amount is required");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        if (amount.scale() > 4) {
            throw new IllegalArgumentException("Amount must have at most 4 decimal places");
        }
    }
}
