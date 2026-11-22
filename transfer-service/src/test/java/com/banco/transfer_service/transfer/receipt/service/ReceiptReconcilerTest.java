package com.banco.transfer_service.transfer.receipt.service;

import com.banco.transfer_service.transfer.receipt.config.ReceiptS3Properties;
import com.banco.transfer_service.transfer.receipt.domain.ReceiptFailureReason;
import com.banco.transfer_service.transfer.receipt.domain.ReceiptStatus;
import com.banco.transfer_service.transfer.receipt.domain.TransferReceipt;
import com.banco.transfer_service.transfer.receipt.repository.TransferReceiptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptReconcilerTest {

    @Mock
    private TransferReceiptRepository repository;
    @Mock
    private S3Client s3Client;

    private ReceiptS3Properties properties;
    private ReceiptReconciler reconciler;

    @BeforeEach
    void setUp() {
        properties = new ReceiptS3Properties();
        properties.getUpload().setMaxAttempts(3);
        properties.getUpload().setRetryBackoff(Duration.ofSeconds(5));
        properties.getUpload().setMissingGrace(Duration.ofMinutes(2));
        reconciler = new ReceiptReconciler(repository, properties, s3Client);
    }

    @Test
    void processClaimed_whenObjectExistsAndChecksumMatches_marksActive() {
        TransferReceipt receipt = pending(1, Instant.now());
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .checksumSHA256(base64(receipt.getSha256Hex()))
                .versionId("v1")
                .build());
        when(repository.markActive(receipt.getId(), worker(), "v1")).thenReturn(1);

        reconciler.processClaimed(receipt, worker());

        verify(repository).markActive(receipt.getId(), worker(), "v1");
    }

    @Test
    void processClaimed_whenChecksumMismatch_marksFailed() {
        TransferReceipt receipt = pending(1, Instant.now());
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .checksumSHA256(Base64.getEncoder().encodeToString(new byte[32]))
                .build());
        when(repository.markFailed(eq(receipt.getId()), eq(worker()),
                eq(ReceiptFailureReason.CHECKSUM_MISMATCH.name()), any())).thenReturn(1);

        reconciler.processClaimed(receipt, worker());

        verify(repository).markFailed(eq(receipt.getId()), eq(worker()),
                eq(ReceiptFailureReason.CHECKSUM_MISMATCH.name()), any());
    }

    @Test
    void processClaimed_whenMissingBeforeGrace_reschedules() {
        TransferReceipt receipt = pending(1, Instant.now());
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        when(repository.reschedule(eq(receipt.getId()), eq(worker()), eq(5L), any())).thenReturn(1);

        reconciler.processClaimed(receipt, worker());

        verify(repository).reschedule(eq(receipt.getId()), eq(worker()), eq(5L), any());
    }

    @Test
    void processClaimed_whenMissingAfterGrace_marksMissing() {
        TransferReceipt receipt = pending(1, Instant.now().minus(Duration.ofMinutes(5)));
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        when(repository.markFailed(eq(receipt.getId()), eq(worker()),
                eq(ReceiptFailureReason.MISSING_OBJECT.name()), any())).thenReturn(1);

        reconciler.processClaimed(receipt, worker());

        verify(repository).markFailed(eq(receipt.getId()), eq(worker()),
                eq(ReceiptFailureReason.MISSING_OBJECT.name()), any());
    }

    @Test
    void processClaimed_whenS3TransientError_reschedules() {
        TransferReceipt receipt = pending(1, Instant.now());
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(503).message("down").build());
        when(repository.reschedule(eq(receipt.getId()), eq(worker()), eq(5L), any())).thenReturn(1);

        reconciler.processClaimed(receipt, worker());

        verify(repository).reschedule(eq(receipt.getId()), eq(worker()), eq(5L), any());
    }

    @Test
    void processClaimed_whenMaxAttemptsReached_marksUnexpectedError() {
        TransferReceipt receipt = pending(3, Instant.now());
        when(repository.markFailed(eq(receipt.getId()), eq(worker()),
                eq(ReceiptFailureReason.UNEXPECTED_ERROR.name()), any())).thenReturn(1);

        reconciler.processClaimed(receipt, worker());

        verify(repository).markFailed(eq(receipt.getId()), eq(worker()),
                eq(ReceiptFailureReason.UNEXPECTED_ERROR.name()), any());
    }

    private static TransferReceipt pending(int attempts, Instant createdAt) {
        TransferReceipt receipt = TransferReceipt.pending(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "11".repeat(32),
                "transfers/t/receipts/r/file.pdf", "application/pdf", "file.pdf", 10,
                "bucket", UUID.randomUUID(), Instant.now().plusSeconds(60)
        );
        org.springframework.test.util.ReflectionTestUtils.setField(receipt, "attempts", attempts);
        org.springframework.test.util.ReflectionTestUtils.setField(receipt, "createdAt", createdAt);
        org.springframework.test.util.ReflectionTestUtils.setField(receipt, "status", ReceiptStatus.PENDING);
        return receipt;
    }

    private static UUID worker() {
        return UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    private static String base64(String hex) {
        return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex));
    }
}
