package com.banco.transfer_service.transfer.receipt.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferReceiptTest {

    @Test
    void pending_setsInitialLeaseAttemptsAndNextAttempt() {
        Instant leaseExpiresAt = Instant.parse("2026-11-22T00:00:00Z");

        TransferReceipt receipt = pendingReceipt(leaseExpiresAt);

        assertThat(receipt.getStatus()).isEqualTo(ReceiptStatus.PENDING);
        assertThat(receipt.getAttempts()).isEqualTo(1);
        assertThat(receipt.getLeaseExpiresAt()).isEqualTo(leaseExpiresAt);
        assertThat(receipt.getNextAttemptAt()).isEqualTo(leaseExpiresAt);
        assertThat(receipt.getFailureReason()).isNull();
        assertThat(receipt.getLastError()).isNull();
    }

    @Test
    void markActive_clearsLeaseFailureAndSetsConfirmation() {
        TransferReceipt receipt = pendingReceipt(Instant.parse("2026-11-22T00:00:00Z"));
        Instant confirmedAt = Instant.parse("2026-11-22T00:00:05Z");

        receipt.markActive("version-1", confirmedAt);

        assertThat(receipt.getStatus()).isEqualTo(ReceiptStatus.ACTIVE);
        assertThat(receipt.getStorageVersionId()).isEqualTo("version-1");
        assertThat(receipt.getConfirmedAt()).isEqualTo(confirmedAt);
        assertThat(receipt.getLeaseOwner()).isNull();
        assertThat(receipt.getLeaseExpiresAt()).isNull();
        assertThat(receipt.getNextAttemptAt()).isNull();
        assertThat(receipt.getFailureReason()).isNull();
        assertThat(receipt.getLastError()).isNull();
    }

    @Test
    void scheduleRetry_clearsLeaseOwnerAndKeepsPending() {
        TransferReceipt receipt = pendingReceipt(Instant.parse("2026-11-22T00:00:00Z"));
        Instant nextAttemptAt = Instant.parse("2026-11-22T00:00:30Z");

        receipt.scheduleRetry(nextAttemptAt, "storage timeout");

        assertThat(receipt.getStatus()).isEqualTo(ReceiptStatus.PENDING);
        assertThat(receipt.getNextAttemptAt()).isEqualTo(nextAttemptAt);
        assertThat(receipt.getLeaseOwner()).isNull();
        assertThat(receipt.getLeaseExpiresAt()).isNull();
        assertThat(receipt.getLastError()).isEqualTo("storage timeout");
    }

    @Test
    void markFailed_setsReasonAndClearsLease() {
        TransferReceipt receipt = pendingReceipt(Instant.parse("2026-11-22T00:00:00Z"));

        receipt.markFailed(ReceiptFailureReason.CHECKSUM_MISMATCH, "digest mismatch");

        assertThat(receipt.getStatus()).isEqualTo(ReceiptStatus.FAILED);
        assertThat(receipt.getFailureReason()).isEqualTo(ReceiptFailureReason.CHECKSUM_MISMATCH);
        assertThat(receipt.getLastError()).isEqualTo("digest mismatch");
        assertThat(receipt.getLeaseOwner()).isNull();
        assertThat(receipt.getLeaseExpiresAt()).isNull();
        assertThat(receipt.getNextAttemptAt()).isNull();
    }

    @Test
    void releaseAmbiguousLease_clearsOwnerButKeepsPending() {
        TransferReceipt receipt = pendingReceipt(Instant.parse("2026-11-22T00:00:00Z"));
        Instant nextAttemptAt = Instant.parse("2026-11-22T00:05:00Z");

        receipt.releaseAmbiguousLease(nextAttemptAt);

        assertThat(receipt.getStatus()).isEqualTo(ReceiptStatus.PENDING);
        assertThat(receipt.getNextAttemptAt()).isEqualTo(nextAttemptAt);
        assertThat(receipt.getLeaseOwner()).isNull();
        assertThat(receipt.getLeaseExpiresAt()).isNull();
    }

    @Test
    void terminalReceiptCannotBeRetried() {
        TransferReceipt receipt = pendingReceipt(Instant.parse("2026-11-22T00:00:00Z"));
        receipt.markActive("version-1", Instant.parse("2026-11-22T00:00:05Z"));

        assertThatThrownBy(() -> receipt.scheduleRetry(Instant.parse("2026-11-22T00:01:00Z"), "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schedule retry");
    }

    private static TransferReceipt pendingReceipt(Instant leaseExpiresAt) {
        return TransferReceipt.pending(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a".repeat(64),
                "transfers/1/receipts/1/a.pdf",
                "application/pdf",
                "comprobante.pdf",
                128,
                "banco-comprobantes-local",
                UUID.randomUUID(),
                leaseExpiresAt
        );
    }
}
