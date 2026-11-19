package com.banco.transfer_service.transfer.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();

    @Test
    void initiate_createsPendingTransferWithCorrelationId() {
        Transfer transfer = transfer(TransferStatus.PENDING, "corr-123");

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(transfer.getCorrelationId()).isEqualTo("corr-123");
        assertThat(transfer.getReference()).isEqualTo("TRX-1");
        assertThat(transfer.getAmount().getAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    void initiate_whenAccountsAreEqual_throwsIllegalArgument() {
        assertThatThrownBy(() -> Transfer.initiate(
                CUSTOMER_ID, SOURCE, SOURCE, money(), "TRX-1", "corr-123"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be different");
    }

    @Test
    void initiate_whenReferenceIsInvalid_throwsIllegalArgument() {
        assertThatThrownBy(() -> Transfer.initiate(
                CUSTOMER_ID, SOURCE, TARGET, money(), " ", "corr-123"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid transfer reference");
    }

    @Test
    void happyPath_transitionsToCompletedAndClearsRetryState() {
        Transfer transfer = transfer(TransferStatus.PENDING, "corr-123");
        ReflectionTestUtils.setField(transfer, "nextAttemptAt", Instant.now());
        ReflectionTestUtils.setField(transfer, "failureReason", "old reason");

        transfer.markDebited();
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.DEBITED);
        assertThat(transfer.getNextAttemptAt()).isNull();
        assertThat(transfer.getFailureReason()).isNull();

        transfer.markCompleting();
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETING);

        transfer.markCompleted();
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.getFailureReason()).isNull();
    }

    @Test
    void compensation_transitionsToCompensatedAndKeepsReason() {
        Transfer transfer = transfer(TransferStatus.DEBITED, "corr-123");

        transfer.markCompensating("credit failed");
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATING);
        assertThat(transfer.getFailureReason()).isEqualTo("credit failed");

        transfer.markCompensated();
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATED);
    }

    @Test
    void markFailed_fromPending_keepsReason() {
        Transfer transfer = transfer(TransferStatus.PENDING, "corr-123");

        transfer.markFailed("debit rejected");

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.getFailureReason()).isEqualTo("debit rejected");
    }

    @Test
    void scheduleRetry_setsNextAttemptAndReasonWithoutChangingStatus() {
        Transfer transfer = transfer(TransferStatus.PENDING, "corr-123");
        Instant nextAttempt = Instant.now().plusSeconds(30);

        transfer.scheduleRetry(nextAttempt, "unavailable");

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(transfer.getNextAttemptAt()).isEqualTo(nextAttempt);
        assertThat(transfer.getFailureReason()).isEqualTo("unavailable");
    }

    @Test
    void markCompleted_fromPending_throwsIllegalState() {
        Transfer transfer = transfer(TransferStatus.PENDING, "corr-123");

        assertThatThrownBy(transfer::markCompleted)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mark completed");
    }

    @Test
    void failureReason_isTruncatedToColumnLength() {
        Transfer transfer = transfer(TransferStatus.PENDING, "corr-123");

        transfer.markFailed("x".repeat(400));

        assertThat(transfer.getFailureReason()).hasSize(255);
    }

    private Money money() {
        return Money.of(new BigDecimal("10.00"), "EUR");
    }

    private Transfer transfer(TransferStatus status, String correlationId) {
        Transfer transfer = Transfer.initiate(
                CUSTOMER_ID, SOURCE, TARGET, money(), "TRX-1", correlationId
        );
        ReflectionTestUtils.setField(transfer, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(transfer, "status", status);
        return transfer;
    }
}
