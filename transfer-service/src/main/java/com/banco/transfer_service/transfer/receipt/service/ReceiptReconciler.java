package com.banco.transfer_service.transfer.receipt.service;

import com.banco.transfer_service.transfer.receipt.config.ReceiptS3Properties;
import com.banco.transfer_service.transfer.receipt.domain.ReceiptFailureReason;
import com.banco.transfer_service.transfer.receipt.domain.TransferReceipt;
import com.banco.transfer_service.transfer.receipt.repository.TransferReceiptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "transfer.receipts.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class ReceiptReconciler {

    private final TransferReceiptRepository receiptRepository;
    private final ReceiptS3Properties properties;
    private final S3Client s3Client;

    @Scheduled(fixedDelayString = "${transfer.receipts.reconcile.interval:1m}")
    @Transactional
    public void reconcile() {
        UUID workerId = UUID.randomUUID();
        ReceiptS3Properties.Upload upload = properties.getUpload();
        List<TransferReceipt> claimed = receiptRepository.claimPending(
                workerId,
                properties.getReconcile().getBatchSize(),
                upload.getLeaseDuration().toSeconds(),
                upload.getMaxAttempts()
        );
        for (TransferReceipt receipt : claimed) {
            processClaimed(receipt, workerId);
        }
    }

    void processClaimed(TransferReceipt receipt, UUID workerId) {
        if (receipt.getAttempts() >= properties.getUpload().getMaxAttempts()) {
            markFailed(receipt, workerId, ReceiptFailureReason.UNEXPECTED_ERROR,
                    "Receipt reached max attempts without a confirmed upload");
            return;
        }

        try {
            HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(receipt.getStorageBucket())
                    .key(receipt.getObjectKey())
                    .build());
            if (checksumMatches(receipt, head)) {
                markActive(receipt, workerId, head.versionId());
                return;
            }
            markFailed(receipt, workerId, ReceiptFailureReason.CHECKSUM_MISMATCH,
                    "S3 checksum did not match stored receipt hash");
        } catch (NoSuchKeyException ex) {
            handleMissing(receipt, workerId);
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                handleMissing(receipt, workerId);
                return;
            }
            handleS3Error(receipt, workerId, ex);
        } catch (RuntimeException ex) {
            log.warn("Unexpected receipt reconcile failure receiptId={} transferId={}",
                    receipt.getId(), receipt.getTransferId(), ex);
            reschedule(receipt, workerId, truncate(ex.getMessage()));
        }
    }

    private void handleMissing(TransferReceipt receipt, UUID workerId) {
        Instant createdAt = receipt.getCreatedAt() == null ? Instant.now() : receipt.getCreatedAt();
        Duration missingGrace = properties.getUpload().getMissingGrace();
        if (Instant.now().isAfter(createdAt.plus(missingGrace))) {
            markFailed(receipt, workerId, ReceiptFailureReason.MISSING_OBJECT,
                    "Receipt object was not found in S3 after grace period");
            return;
        }
        reschedule(receipt, workerId, "Receipt object not found yet");
    }

    private void handleS3Error(TransferReceipt receipt, UUID workerId, S3Exception ex) {
        if (ex.statusCode() >= 400 && ex.statusCode() < 500) {
            markFailed(receipt, workerId, ReceiptFailureReason.UPLOAD_ERROR,
                    truncate("S3 rejected receipt reconciliation: " + ex.statusCode()));
            return;
        }
        reschedule(receipt, workerId, truncate("S3 error during reconciliation: " + ex.statusCode()));
    }

    private boolean checksumMatches(TransferReceipt receipt, HeadObjectResponse head) {
        String checksum = head.checksumSHA256();
        if (checksum == null || checksum.isBlank()) {
            return false;
        }
        String expected = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(receipt.getSha256Hex()));
        return expected.equals(checksum);
    }

    private void markActive(TransferReceipt receipt, UUID workerId, String versionId) {
        int updated = receiptRepository.markActive(receipt.getId(), workerId, versionId);
        if (updated > 0) {
            log.info("Receipt reconciled as ACTIVE receiptId={} transferId={}",
                    receipt.getId(), receipt.getTransferId());
        }
    }

    private void markFailed(TransferReceipt receipt, UUID workerId, ReceiptFailureReason reason, String error) {
        int updated = receiptRepository.markFailed(receipt.getId(), workerId, reason.name(), truncate(error));
        if (updated > 0) {
            log.warn("Receipt reconciled as FAILED receiptId={} transferId={} reason={}",
                    receipt.getId(), receipt.getTransferId(), reason);
        }
    }

    private void reschedule(TransferReceipt receipt, UUID workerId, String error) {
        receiptRepository.reschedule(
                receipt.getId(),
                workerId,
                properties.getUpload().getRetryBackoff().toSeconds(),
                truncate(error)
        );
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
