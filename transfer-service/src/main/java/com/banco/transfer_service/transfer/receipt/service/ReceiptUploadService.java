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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

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

    @Transactional
    public ReceiptResponse upload(UUID transferId, MultipartFile file, CustomerContext customer) {
        TransferResponse transfer = transferService.getById(transferId, customer);
        ReceiptFileValidator.ValidatedReceiptFile validated = fileValidator.validate(
                file, properties.getUpload().getMaxFileSize().toBytes()
        );

        UUID receiptId = UUID.randomUUID();
        UUID leaseOwner = UUID.randomUUID();
        Instant leaseExpiresAt = Instant.now().plus(properties.getUpload().getLeaseDuration());
        String objectKey = objectKey(transfer.id(), receiptId, validated.originalFilename());

        TransferReceipt receipt = TransferReceipt.pending(
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
                leaseExpiresAt
        );
        receiptRepository.saveAndFlush(receipt);

        UploadResult result = putObject(receipt, file, validated);

        if (result instanceof UploadResult.Success success) {
            receipt.markActive(success.versionId(), Instant.now());
            receiptRepository.saveAndFlush(receipt);
            return toResponse(receipt);
        }
        if (result instanceof UploadResult.ChecksumMismatch mismatch) {
            receipt.markFailed(ReceiptFailureReason.CHECKSUM_MISMATCH, truncate(mismatch.message(), 500));
            receiptRepository.saveAndFlush(receipt);
            throw new ReceiptFailedException(toResponse(receipt));
        }
        if (result instanceof UploadResult.DefinitiveFailure failure) {
            receipt.scheduleRetry(
                    Instant.now().plus(properties.getUpload().getRetryBackoff()),
                    truncate(failure.message(), 500)
            );
            receiptRepository.saveAndFlush(receipt);
            throw new ReceiptStorageUnavailableException(receipt.getId().toString(), failure.message(), failure.cause());
        }

        UploadResult.Ambiguous ambiguous = (UploadResult.Ambiguous) result;
        log.warn("Ambiguous receipt upload result receiptId={} transferId={}: {}",
                receipt.getId(), transfer.id(), ambiguous.message());
        receipt.releaseAmbiguousLease(Instant.now().plus(properties.getUpload().getRetryBackoff()));
        receiptRepository.saveAndFlush(receipt);
        TransferReceipt refreshed = receiptRepository.findById(receipt.getId()).orElse(receipt);
        ReceiptResponse response = toResponse(refreshed);
        if (refreshed.getStatus() == ReceiptStatus.ACTIVE) {
            return response;
        }
        if (refreshed.getStatus() == ReceiptStatus.FAILED) {
            throw new ReceiptFailedException(response);
        }
        throw new ReceiptUploadPendingException(response);
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
                return new UploadResult.ChecksumMismatch("S3 checksum did not match uploaded content");
            }
            return new UploadResult.Success(response.versionId());
        } catch (S3Exception ex) {
            if (isChecksumMismatch(ex)) {
                return new UploadResult.ChecksumMismatch(CHECKSUM_MISMATCH);
            }
            if (ex.statusCode() >= 400 && ex.statusCode() < 500) {
                return new UploadResult.DefinitiveFailure("S3 rejected the upload", ex);
            }
            if (ex.statusCode() >= 500) {
                return new UploadResult.DefinitiveFailure("S3 is unavailable", ex);
            }
            return new UploadResult.Ambiguous("S3 returned an ambiguous error", ex);
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

    private static boolean isChecksumMismatch(S3Exception ex) {
        String code = ex.awsErrorDetails() == null ? null : ex.awsErrorDetails().errorCode();
        String message = ex.awsErrorDetails() == null ? ex.getMessage() : ex.awsErrorDetails().errorMessage();
        return "BadDigest".equalsIgnoreCase(code)
                || (message != null && message.toLowerCase().contains(CHECKSUM_MISMATCH));
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
        return new ReceiptResponse(
                receipt.getId(),
                receipt.getTransferId(),
                receipt.getCustomerId(),
                receipt.getStatus(),
                receipt.getContentType(),
                receipt.getOriginalFilename(),
                receipt.getSizeBytes(),
                receipt.getSha256Hex(),
                receipt.getCreatedAt(),
                receipt.getConfirmedAt(),
                receipt.getFailureReason()
        );
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
