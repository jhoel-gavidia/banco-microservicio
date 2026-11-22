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
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
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
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelayString = "${transfer.receipts.reconcile.interval:1m}")
    public void reconcile() {
        UUID workerId = UUID.randomUUID();
        ReceiptS3Properties.Upload upload = properties.getUpload();
        List<TransferReceipt> claimed = inTx(() -> receiptRepository.claimPending(
                workerId,
                properties.getReconcile().getBatchSize(),
                upload.getLeaseDuration().toSeconds(),
                upload.getMaxAttempts()
        ));
        for (TransferReceipt receipt : claimed) {
            processClaimed(receipt, workerId);
        }
    }

    void processClaimed(TransferReceipt receipt, UUID workerId) {
        int maxAttempts = properties.getUpload().getMaxAttempts();
        boolean maxAttemptsReached = receipt.getAttempts() >= maxAttempts;

        try {
            HeadObjectResponse head = headObject(receipt);
            if (checksumMatches(receipt, head)) {
                if (maxAttemptsReached) {
                    log.warn("Receipt reached max attempts but valid S3 object found; marking ACTIVE receiptId={} transferId={} attempts={}",
                            receipt.getId(), receipt.getTransferId(), receipt.getAttempts());
                }
                markActive(receipt, workerId, head.versionId());
                return;
            }
            markFailed(receipt, workerId, ReceiptFailureReason.CHECKSUM_MISMATCH,
                    "S3 checksum did not match stored receipt hash");
        } catch (NoSuchKeyException ex) {
            handleMissing(receipt, workerId, maxAttemptsReached);
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                handleMissing(receipt, workerId, maxAttemptsReached);
                return;
            }
            handleS3Error(receipt, workerId, ex, maxAttemptsReached);
        } catch (ChecksumUnavailableException ex) {
            log.warn("Receipt checksum unavailable during reconciliation receiptId={} transferId={} workerId={}",
                    receipt.getId(), receipt.getTransferId(), workerId);
            if (maxAttemptsReached) {
                markFailed(receipt, workerId, ReceiptFailureReason.UNEXPECTED_ERROR,
                        "Receipt reached max attempts and S3 checksum could not be verified");
                return;
            }
            reschedule(receipt, workerId, truncate(ex.getMessage()));
        } catch (ApiCallTimeoutException | ApiCallAttemptTimeoutException ex) {
            handleTransientOrAmbiguous(receipt, workerId, ex, maxAttemptsReached,
                    "S3 HeadObject timed out during reconciliation");
        } catch (RuntimeException ex) {
            log.warn("Unexpected receipt reconcile failure receiptId={} transferId={} workerId={}",
                    receipt.getId(), receipt.getTransferId(), workerId, ex);
            handleTransientOrAmbiguous(receipt, workerId, ex, maxAttemptsReached,
                    "Unexpected error during receipt reconciliation");
        }
    }

    private HeadObjectResponse headObject(TransferReceipt receipt) {
        return s3Client.headObject(HeadObjectRequest.builder()
                .bucket(receipt.getStorageBucket())
                .key(receipt.getObjectKey())
                .checksumMode(ChecksumMode.ENABLED)
                .build());
    }

    private void handleMissing(TransferReceipt receipt, UUID workerId, boolean maxAttemptsReached) {
        Instant reference = receipt.getCreatedAt() == null ? Instant.now() : receipt.getCreatedAt();
        Duration missingGrace = properties.getUpload().getMissingGrace();
        if (maxAttemptsReached || Instant.now().isAfter(reference.plus(missingGrace))) {
            ReceiptFailureReason reason = maxAttemptsReached ? ReceiptFailureReason.UNEXPECTED_ERROR : ReceiptFailureReason.MISSING_OBJECT;
            String message = maxAttemptsReached
                    ? "Receipt reached max attempts and object was not found in S3"
                    : "Receipt object was not found in S3 after grace period";
            markFailed(receipt, workerId, reason, message);
            return;
        }
        log.info("Receipt object missing before grace period; rescheduling receiptId={} transferId={} workerId={}",
                receipt.getId(), receipt.getTransferId(), workerId);
        reschedule(receipt, workerId, "Receipt object not found yet");
    }

    private void handleS3Error(TransferReceipt receipt, UUID workerId, S3Exception ex, boolean maxAttemptsReached) {
        int status = ex.statusCode();
        if (isDefinitiveClientError(status)) {
            markFailed(receipt, workerId, ReceiptFailureReason.UPLOAD_ERROR,
                    truncate("S3 rejected receipt reconciliation: status=" + status));
            return;
        }
        handleTransientOrAmbiguous(receipt, workerId, ex, maxAttemptsReached,
                "S3 error during reconciliation: status=" + status);
    }

    private void handleTransientOrAmbiguous(
            TransferReceipt receipt, UUID workerId, RuntimeException ex, boolean maxAttemptsReached, String message) {
        if (maxAttemptsReached) {
            markFailed(receipt, workerId, ReceiptFailureReason.UNEXPECTED_ERROR,
                    truncate(message + " after max attempts"));
            return;
        }
        log.warn("Transient/ambiguous receipt reconciliation error receiptId={} transferId={} workerId={} message={}",
                receipt.getId(), receipt.getTransferId(), workerId, message, ex);
        reschedule(receipt, workerId, truncate(message));
    }

    private static boolean isDefinitiveClientError(int status) {
        return status == 400 || status == 401 || status == 403 || status == 405 || status == 410 || status == 422;
    }

    private boolean checksumMatches(TransferReceipt receipt, HeadObjectResponse head) {
        String checksum = head.checksumSHA256();
        if (checksum == null || checksum.isBlank()) {
            throw new ChecksumUnavailableException("S3 head response did not include SHA-256 checksum");
        }
        String expected = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(receipt.getSha256Hex()));
        return expected.equals(checksum);
    }

    private void markActive(TransferReceipt receipt, UUID workerId, String versionId) {
        int updated = inTx(() -> receiptRepository.markActive(receipt.getId(), workerId, versionId));
        if (updated > 0) {
            log.info("Receipt reconciled as ACTIVE receiptId={} transferId={} workerId={}",
                    receipt.getId(), receipt.getTransferId(), workerId);
        } else {
            logTransitionIgnored(receipt, workerId, "markActive");
        }
    }

    private void markFailed(TransferReceipt receipt, UUID workerId, ReceiptFailureReason reason, String error) {
        int updated = inTx(() -> receiptRepository.markFailed(receipt.getId(), workerId, reason.name(), truncate(error)));
        if (updated > 0) {
            log.warn("Receipt reconciled as FAILED receiptId={} transferId={} workerId={} reason={}",
                    receipt.getId(), receipt.getTransferId(), workerId, reason);
        } else {
            logTransitionIgnored(receipt, workerId, "markFailed:" + reason);
        }
    }

    private void reschedule(TransferReceipt receipt, UUID workerId, String error) {
        int updated = inTx(() -> receiptRepository.reschedule(
                receipt.getId(),
                workerId,
                properties.getUpload().getRetryBackoff().toSeconds(),
                truncate(error)
        ));
        if (updated == 0) {
            logTransitionIgnored(receipt, workerId, "reschedule");
        }
    }

    private void logTransitionIgnored(TransferReceipt receipt, UUID workerId, String transition) {
        TransferReceipt current = inTx(() -> receiptRepository.findById(receipt.getId()).orElse(receipt));
        log.warn("Receipt transition ignored transition={} receiptId={} transferId={} workerId={} actualStatus={} actualLeaseOwner={}",
                transition, receipt.getId(), receipt.getTransferId(), workerId, current.getStatus(), current.getLeaseOwner());
    }

    private <T> T inTx(java.util.function.Supplier<T> action) {
        T result = transactionTemplate.execute(status -> action.get());
        if (result == null) {
            throw new IllegalStateException("Transaction returned null");
        }
        return result;
    }

    private int inTx(java.util.function.IntSupplier action) {
        Integer result = transactionTemplate.execute(status -> action.getAsInt());
        return result == null ? 0 : result;
    }

    private static final class ChecksumUnavailableException extends RuntimeException {
        private ChecksumUnavailableException(String message) {
            super(message);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
