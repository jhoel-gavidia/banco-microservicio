package com.banco.transfer_service.transfer.receipt.service;

import com.banco.transfer_service.security.CustomerContext;
import com.banco.transfer_service.transfer.dto.TransferResponse;
import com.banco.transfer_service.transfer.receipt.config.ReceiptS3Properties;
import com.banco.transfer_service.transfer.receipt.domain.ReceiptFailureReason;
import com.banco.transfer_service.transfer.receipt.domain.ReceiptStatus;
import com.banco.transfer_service.transfer.receipt.domain.TransferReceipt;
import com.banco.transfer_service.transfer.receipt.dto.ReceiptResponse;
import com.banco.transfer_service.transfer.receipt.exception.ReceiptFailedException;
import com.banco.transfer_service.transfer.receipt.exception.ReceiptStorageUnavailableException;
import com.banco.transfer_service.transfer.receipt.exception.ReceiptUploadPendingException;
import com.banco.transfer_service.transfer.receipt.repository.TransferReceiptRepository;
import com.banco.transfer_service.transfer.service.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReceiptUploadService {

    private static final String CHECKSUM_MISMATCH = "checksum mismatch";

    private final TransferService transferService;
    private final TransferReceiptRepository receiptRepository;
    private final ReceiptFileValidator fileValidator;
    private final ReceiptS3Properties properties;
    private final S3Client s3Client;
    private final ReceiptLeaseHeartbeat leaseHeartbeat;
    private final TransactionTemplate transactionTemplate;

    public ReceiptResponse upload(UUID transferId, MultipartFile file, CustomerContext customer) {
        TransferResponse transfer = transferService.getById(transferId, customer);
        ReceiptFileValidator.ValidatedReceiptFile validated = fileValidator.validate(
                file, properties.getUpload().getMaxFileSize().toBytes()
        );

        UUID receiptId = UUID.randomUUID();
        UUID leaseOwner = UUID.randomUUID();
        String objectKey = objectKey(transfer.id(), receiptId, validated.originalFilename());

        TransferReceipt receipt = createPending(receiptId, transfer, validated, objectKey, leaseOwner);

        ScheduledFuture<?> heartbeat = leaseHeartbeat.start(receiptId, leaseOwner);
        try {
            UploadResult result = putObject(receipt, file, validated);
            return finalizeUpload(receipt, leaseOwner, result, transfer.id());
        } finally {
            leaseHeartbeat.stop(heartbeat);
        }
    }

    private ReceiptResponse finalizeUpload(TransferReceipt receipt, UUID leaseOwner, UploadResult result, UUID transferId) {
        if (result instanceof UploadResult.Success success) {
            int updated = inTx(() -> receiptRepository.markActive(receipt.getId(), leaseOwner, success.versionId()));
            TransferReceipt current = refresh(receipt.getId(), receipt);
            if (updated > 0) {
                log.info("Receipt marked ACTIVE receiptId={} transferId={} workerId={}", receipt.getId(), transferId, leaseOwner);
                return toResponse(current);
            }
            log.warn("Receipt markActive affected 0 rows receiptId={} transferId={} workerId={} actualStatus={}",
                    receipt.getId(), transferId, leaseOwner, current.getStatus());
            return toResponseOrThrow(current, result);
        }
        if (result instanceof UploadResult.ChecksumMismatch mismatch) {
            int updated = inTx(() -> receiptRepository.markFailed(
                    receipt.getId(), leaseOwner, ReceiptFailureReason.CHECKSUM_MISMATCH.name(), truncate(mismatch.message(), 500)));
            TransferReceipt current = refresh(receipt.getId(), receipt);
            if (updated > 0) {
                throw new ReceiptFailedException(toResponse(current));
            }
            log.warn("Receipt markFailed(checksum) affected 0 rows receiptId={} transferId={} workerId={} actualStatus={}",
                    receipt.getId(), transferId, leaseOwner, current.getStatus());
            return toResponseOrThrow(current, result);
        }
        if (result instanceof UploadResult.DefinitiveFailure failure) {
            int updated = inTx(() -> receiptRepository.reschedule(
                    receipt.getId(), leaseOwner, properties.getUpload().getRetryBackoff().toSeconds(), truncate(failure.message(), 500)));
            TransferReceipt current = refresh(receipt.getId(), receipt);
            if (updated > 0) {
                throw new ReceiptStorageUnavailableException(receipt.getId().toString(), failure.message(), failure.cause());
            }
            log.warn("Receipt reschedule after definitive failure affected 0 rows receiptId={} transferId={} workerId={} actualStatus={}",
                    receipt.getId(), transferId, leaseOwner, current.getStatus());
            return toResponseOrThrow(current, result);
        }

        UploadResult.Ambiguous ambiguous = (UploadResult.Ambiguous) result;
        log.warn("Ambiguous receipt upload result receiptId={} transferId={} workerId={}: {}",
                receipt.getId(), transferId, leaseOwner, ambiguous.message());
        int updated = inTx(() -> receiptRepository.releaseAmbiguousLease(
                receipt.getId(), leaseOwner, properties.getUpload().getRetryBackoff().toSeconds()));
        TransferReceipt refreshed = refresh(receipt.getId(), receipt);
        if (updated == 0) {
            log.warn("Receipt releaseAmbiguousLease affected 0 rows receiptId={} transferId={} workerId={} actualStatus={}",
                    receipt.getId(), transferId, leaseOwner, refreshed.getStatus());
        }
        return toResponseOrThrow(refreshed, result);
    }

    private TransferReceipt createPending(
            UUID receiptId, TransferResponse transfer, ReceiptFileValidator.ValidatedReceiptFile validated,
            String objectKey, UUID leaseOwner
    ) {
        return inTx(() -> {
            TransferReceipt pending = TransferReceipt.pending(
                    receiptId,
                    transfer.id(),
                    transfer.customerId(),
                    validated.sha256Hex(),
                    objectKey,
                    validated.contentType(),
                    validated.originalFilename(),
                    validated.sizeBytes(),
                    properties.getS3().getBucket(),
                    leaseOwner,
                    java.time.Instant.now().plus(properties.getUpload().getLeaseDuration())
            );
            return receiptRepository.saveAndFlush(pending);
        });
    }

    private ReceiptResponse toResponseOrThrow(TransferReceipt current, UploadResult result) {
        ReceiptResponse response = toResponse(current);
        if (current.getStatus() == ReceiptStatus.ACTIVE) {
            return response;
        }
        if (current.getStatus() == ReceiptStatus.FAILED) {
            throw new ReceiptFailedException(response);
        }
        if (result instanceof UploadResult.DefinitiveFailure failure) {
            throw new ReceiptStorageUnavailableException(current.getId().toString(), failure.message(), failure.cause());
        }
        throw new ReceiptUploadPendingException(response);
    }

    private TransferReceipt refresh(UUID receiptId, TransferReceipt fallback) {
        return inTx(() -> receiptRepository.findById(receiptId).orElse(fallback));
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

    private UploadResult putObject(TransferReceipt receipt, MultipartFile file, ReceiptFileValidator.ValidatedReceiptFile validated) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(receipt.getStorageBucket())
                    .key(receipt.getObjectKey())
                    .contentType(receipt.getContentType())
                    .contentLength(receipt.getSizeBytes())
                    .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                    .checksumSHA256(base64Sha256(validated.sha256Hex()))
                    .build();

            PutObjectResponse response = s3Client.putObject(request, requestBody(file, validated.sizeBytes()));
            String checksum = response.checksumSHA256();
            if (checksum != null && !checksum.isBlank() && !checksum.equals(base64Sha256(validated.sha256Hex()))) {
                UploadResult verified = verifyAfterChecksumException(receipt, validated);
                if (verified != null) {
                    return verified;
                }
                return new UploadResult.ChecksumMismatch("S3 checksum did not match uploaded content");
            }
            return new UploadResult.Success(response.versionId());
        } catch (S3Exception ex) {
            if (isChecksumMismatch(ex)) {
                UploadResult verified = verifyAfterChecksumException(receipt, validated);
                if (verified != null) {
                    return verified;
                }
                return new UploadResult.Ambiguous("S3 reported checksum mismatch; verification pending", ex);
            }
            return classifyS3Exception(ex);
        } catch (ApiCallTimeoutException | ApiCallAttemptTimeoutException ex) {
            return new UploadResult.Ambiguous("S3 upload timed out", ex);
        } catch (UncheckedIOException ex) {
            return new UploadResult.DefinitiveFailure("Receipt file could not be read for upload", ex);
        } catch (RuntimeException ex) {
            return new UploadResult.Ambiguous("Unexpected S3 upload failure", ex);
        }
    }

    private RequestBody requestBody(MultipartFile file, long size) {
        return RequestBody.fromContentProvider(() -> {
            try {
                return file.getInputStream();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }, size, file.getContentType());
    }

    private UploadResult verifyAfterChecksumException(
            TransferReceipt receipt, ReceiptFileValidator.ValidatedReceiptFile validated) {
        try {
            var head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(receipt.getStorageBucket())
                    .key(receipt.getObjectKey())
                    .checksumMode(ChecksumMode.ENABLED)
                    .build());
            String checksum = head.checksumSHA256();
            if (checksum == null || checksum.isBlank()) {
                return null;
            }
            String expected = base64Sha256(validated.sha256Hex());
            if (expected.equals(checksum)) {
                log.warn("S3 returned checksum mismatch but HeadObject confirms object checksum; treating as success receiptId={}", receipt.getId());
                return new UploadResult.Success(head.versionId());
            }
            return new UploadResult.ChecksumMismatch("S3 checksum did not match uploaded content");
        } catch (RuntimeException ex) {
            log.debug("Could not verify receipt after checksum exception receiptId={}", receipt.getId(), ex);
            return null;
        }
    }

    private static boolean isChecksumMismatch(S3Exception ex) {
        String code = ex.awsErrorDetails() == null ? null : ex.awsErrorDetails().errorCode();
        String message = ex.awsErrorDetails() == null ? ex.getMessage() : ex.awsErrorDetails().errorMessage();
        return "BadDigest".equalsIgnoreCase(code)
                || (message != null && message.toLowerCase().contains(CHECKSUM_MISMATCH));
    }

    private UploadResult classifyS3Exception(S3Exception ex) {
        int status = ex.statusCode();
        String code = ex.awsErrorDetails() == null ? "" : String.valueOf(ex.awsErrorDetails().errorCode());
        if (status == 400 || status == 401 || status == 403 || status == 405 || status == 410 || status == 422) {
            return new UploadResult.DefinitiveFailure("S3 rejected the upload: status=" + status + " code=" + code, ex);
        }
        if (status == 404) {
            return new UploadResult.Ambiguous("S3 returned not found during upload: status=" + status + " code=" + code, ex);
        }
        if (status == 408 || status == 409 || status == 429 || status >= 500) {
            return new UploadResult.Ambiguous("S3 upload outcome ambiguous/transient: status=" + status + " code=" + code, ex);
        }
        if (status >= 400 && status < 500) {
            return new UploadResult.DefinitiveFailure("S3 rejected the upload: status=" + status + " code=" + code, ex);
        }
        return new UploadResult.Ambiguous("S3 returned an ambiguous error: status=" + status + " code=" + code, ex);
    }

    private static String objectKey(UUID transferId, UUID receiptId, String filename) {
        return "transfers/" + transferId + "/receipts/" + receiptId + "/" + filename;
    }

    private static String base64Sha256(String sha256Hex) {
        return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256Hex));
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static ReceiptResponse toResponse(TransferReceipt receipt) {
        return ReceiptResponse.from(receipt);
    }

    private sealed interface UploadResult permits UploadResult.Success, UploadResult.DefinitiveFailure,
            UploadResult.ChecksumMismatch, UploadResult.Ambiguous {
        record Success(String versionId) implements UploadResult {
        }

        record DefinitiveFailure(String message, RuntimeException cause) implements UploadResult {
        }

        record ChecksumMismatch(String message) implements UploadResult {
        }

        record Ambiguous(String message, RuntimeException cause) implements UploadResult {
        }
    }
}
