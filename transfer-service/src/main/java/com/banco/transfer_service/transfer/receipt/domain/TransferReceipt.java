package com.banco.transfer_service.transfer.receipt.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "transfer_receipts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransferReceipt {

    @Id
    private UUID id;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReceiptStatus status;

    @Column(name = "sha256_hex", nullable = false, length = 64)
    private String sha256Hex;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_bucket", nullable = false, length = 255)
    private String storageBucket;

    @Column(name = "storage_version_id", length = 255)
    private String storageVersionId;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "lease_owner")
    private UUID leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 64)
    private ReceiptFailureReason failureReason;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

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

    public static TransferReceipt pending(
            UUID id,
            UUID transferId,
            UUID customerId,
            String sha256Hex,
            String objectKey,
            String contentType,
            String originalFilename,
            long sizeBytes,
            String storageBucket,
            UUID leaseOwner,
            Instant leaseExpiresAt
    ) {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(transferId, "transferId is required");
        Objects.requireNonNull(customerId, "customerId is required");
        Objects.requireNonNull(leaseOwner, "leaseOwner is required");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt is required");
        requireText(sha256Hex, "sha256Hex is required");
        requireText(objectKey, "objectKey is required");
        requireText(contentType, "contentType is required");
        requireText(originalFilename, "originalFilename is required");
        requireText(storageBucket, "storageBucket is required");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }

        TransferReceipt receipt = new TransferReceipt();
        receipt.id = id;
        receipt.transferId = transferId;
        receipt.customerId = customerId;
        receipt.status = ReceiptStatus.PENDING;
        receipt.sha256Hex = sha256Hex;
        receipt.objectKey = objectKey;
        receipt.contentType = contentType;
        receipt.originalFilename = originalFilename;
        receipt.sizeBytes = sizeBytes;
        receipt.storageBucket = storageBucket;
        receipt.attempts = 1;
        receipt.nextAttemptAt = leaseExpiresAt;
        receipt.leaseOwner = leaseOwner;
        receipt.leaseExpiresAt = leaseExpiresAt;
        receipt.updatedAt = Instant.now();
        return receipt;
    }

    public void markActive(String storageVersionId, Instant confirmedAt) {
        requirePending("mark active");
        this.status = ReceiptStatus.ACTIVE;
        this.storageVersionId = storageVersionId;
        this.confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt is required");
        this.failureReason = null;
        this.lastError = null;
        clearLease();
        touch();
    }

    public void scheduleRetry(Instant nextAttemptAt, String lastError) {
        requirePending("schedule retry");
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt is required");
        this.lastError = truncate(lastError, 500);
        clearLease();
        touch();
    }

    public void markFailed(ReceiptFailureReason reason, String lastError) {
        requirePending("mark failed");
        this.status = ReceiptStatus.FAILED;
        this.failureReason = Objects.requireNonNull(reason, "reason is required");
        this.lastError = truncate(lastError, 500);
        clearLease();
        touch();
    }

    public void releaseAmbiguousLease(Instant nextAttemptAt) {
        requirePending("release ambiguous lease");
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt is required");
        clearLease();
        touch();
    }

    private void requirePending(String operation) {
        if (status != ReceiptStatus.PENDING) {
            throw new IllegalStateException("Cannot " + operation + " a receipt in status " + status);
        }
    }

    private void clearLease() {
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        if (status != ReceiptStatus.PENDING) {
            this.nextAttemptAt = null;
        }
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    @PreUpdate
    void onUpdate() {
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
