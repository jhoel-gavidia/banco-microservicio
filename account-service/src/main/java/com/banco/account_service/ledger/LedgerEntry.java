package com.banco.account_service.ledger;

import com.banco.account_service.account.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 6)
    private LedgerEntryType type;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(name = "reference", nullable = false, length = 100)
    private String reference;

    @org.hibernate.annotations.Generated(
            event = org.hibernate.generator.EventType.INSERT
    )
    @Column(
            name = "entry_seq",
            nullable = false,
            updatable = false,
            insertable = false
    )
    private Long entrySeq;

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

    private LedgerEntry(
            Account account,
            LedgerEntryType type,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String reference
    ) {
        this.account = Objects.requireNonNull(account, "Account is required");
        this.type = Objects.requireNonNull(type, "Type is required");
        this.amount = Objects.requireNonNull(amount, "Amount is required");
        this.balanceAfter = Objects.requireNonNull(balanceAfter, "Balance after is required");
        this.reference = Objects.requireNonNull(reference, "Reference is required");

        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        if (reference.isBlank() || reference.length() > 100) {
            throw new IllegalArgumentException("Invalid reference");
        }
    }

    public static LedgerEntry credit(
            Account account,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String reference
    ) {
        return new LedgerEntry(account, LedgerEntryType.CREDIT, amount, balanceAfter, reference);
    }

    public static LedgerEntry debit(
            Account account,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String reference
    ) {
        return new LedgerEntry(account, LedgerEntryType.DEBIT, amount, balanceAfter, reference);
    }
}
