package com.banco.account_service.account;

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
}
