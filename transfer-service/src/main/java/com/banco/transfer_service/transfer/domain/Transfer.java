package com.banco.transfer_service.transfer.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "transfers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "target_account_id", nullable = false)
    private UUID targetAccountId;

    @Embedded
    private Money amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TransferStatus status;

    @Column(name = "reference", nullable = false, length = 100)
    private String reference;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

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

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Transfer initiate(
            UUID customerId,
            UUID sourceAccountId,
            UUID targetAccountId,
            Money amount,
            String reference
    ) {
        Objects.requireNonNull(customerId, "Customer ID is required");
        Objects.requireNonNull(sourceAccountId, "Source account ID is required");
        Objects.requireNonNull(targetAccountId, "Target account ID is required");
        Objects.requireNonNull(amount, "Amount is required");

        if (sourceAccountId.equals(targetAccountId)) {
            throw new IllegalArgumentException("Source and target accounts must be different");
        }
        if (reference == null || reference.isBlank() || reference.length() > 100) {
            throw new IllegalArgumentException("Invalid transfer reference");
        }

        Transfer transfer = new Transfer();
        transfer.customerId = customerId;
        transfer.sourceAccountId = sourceAccountId;
        transfer.targetAccountId = targetAccountId;
        transfer.amount = amount;
        transfer.reference = reference;
        transfer.status = TransferStatus.PENDING;
        transfer.updatedAt = Instant.now();

        return transfer;
    }

    public void markDebited() {
        requireStatus("mark debited", TransferStatus.PENDING);
        status = TransferStatus.DEBITED;
        touch();
    }

    public void markCompleting() {
        requireStatus("mark completing", TransferStatus.DEBITED);
        status = TransferStatus.COMPLETING;
        touch();
    }

    public void markCompleted() {
        requireStatus("mark completed", TransferStatus.COMPLETING);
        status = TransferStatus.COMPLETED;
        failureReason = null;
        touch();
    }

    public void markCompensating(String reason) {
        requireStatus(
                "mark compensating",
                TransferStatus.DEBITED,
                TransferStatus.COMPLETING
        );
        status = TransferStatus.COMPENSATING;
        failureReason = truncate(reason);
        touch();
    }

    public void markCompensated() {
        requireStatus("mark compensated", TransferStatus.COMPENSATING);
        status = TransferStatus.COMPENSATED;
        touch();
    }

    public void markFailed(String reason) {
        requireStatus(
                "mark failed",
                TransferStatus.PENDING,
                TransferStatus.DEBITED,
                TransferStatus.COMPLETING,
                TransferStatus.COMPENSATING
        );
        status = TransferStatus.FAILED;
        failureReason = truncate(reason);
        touch();
    }

    private void requireStatus(String operation, TransferStatus... expected) {
        if (Arrays.stream(expected).noneMatch(status::equals)) {
            throw new IllegalStateException(
                    "Cannot " + operation + " a transfer in status " + status
            );
        }
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        touch();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}
