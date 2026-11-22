package com.banco.transfer_service.transfer.receipt.service;

import com.banco.transfer_service.transfer.receipt.config.ReceiptS3Properties;
import com.banco.transfer_service.transfer.receipt.domain.TransferReceipt;
import com.banco.transfer_service.transfer.receipt.exception.ReceiptIntegrityException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReceiptStorageService {

    private static final Duration API_CALL_TIMEOUT_MARGIN = Duration.ofSeconds(5);

    private final ReceiptS3Properties properties;
    private final S3Client s3Client;

    public ReceiptFile download(TransferReceipt receipt) {
        ReceiptS3Properties.Download download = properties.getDownload();
        Path receiptsDir = ensureReceiptsDir(download);
        Path tempFile = createTempFile(receiptsDir);

        AtomicBoolean cleaned = new AtomicBoolean(false);
        Runnable cleanup = () -> {
            if (cleaned.compareAndSet(false, true)) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
        };

        try (ResponseInputStream<GetObjectResponse> response = openObject(receipt, download)) {
            validateChecksum(receipt, response.response());
            copyToTemp(response, tempFile, receipt, download);
            InputStream stream = new BufferedInputStream(Files.newInputStream(tempFile));
            return new ReceiptFile(stream, receipt.getContentType(), receipt.getOriginalFilename(),
                    receipt.getSizeBytes(), cleanup);
        } catch (ReceiptIntegrityException ex) {
            cleanup.run();
            throw ex;
        } catch (NoSuchKeyException ex) {
            cleanup.run();
            throw new ReceiptIntegrityException("Receipt object is missing from storage");
        } catch (S3Exception ex) {
            cleanup.run();
            throw new ReceiptIntegrityException("Receipt could not be read from storage");
        } catch (IOException ex) {
            cleanup.run();
            throw new ReceiptIntegrityException("Receipt could not be validated");
        } catch (RuntimeException ex) {
            cleanup.run();
            throw ex;
        }
    }

    private ResponseInputStream<GetObjectResponse> openObject(
            TransferReceipt receipt, ReceiptS3Properties.Download download) {
        Duration apiCallTimeout = download.getTotalSoftDeadline().plus(API_CALL_TIMEOUT_MARGIN);
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(receipt.getStorageBucket())
                .key(receipt.getObjectKey())
                .checksumMode(ChecksumMode.ENABLED)
                .overrideConfiguration(AwsRequestOverrideConfiguration.builder()
                        .apiCallTimeout(apiCallTimeout)
                        .apiCallAttemptTimeout(apiCallTimeout)
                        .build())
                .build();
        return s3Client.getObject(request);
    }

    private void copyToTemp(
            ResponseInputStream<GetObjectResponse> response,
            Path tempFile,
            TransferReceipt receipt,
            ReceiptS3Properties.Download download
    ) throws IOException {
        MessageDigest digest = sha256();
        long total = 0;
        long started = System.nanoTime();
        long lastReadNanos = started;
        byte[] buffer = new byte[(int) download.getStreamBufferSize().toBytes()];

        try (DigestInputStream input = new DigestInputStream(response, digest);
             OutputStream output = Files.newOutputStream(tempFile, StandardOpenOption.TRUNCATE_EXISTING)) {
            while (true) {
                checkDeadlines(response, started, lastReadNanos, download, "waiting for receipt content");
                int read = input.read(buffer);
                if (read == -1) {
                    break;
                }
                lastReadNanos = System.nanoTime();
                total += read;
                if (total > receipt.getSizeBytes()) {
                    abort(response);
                    throw new ReceiptIntegrityException("Receipt size does not match stored metadata");
                }
                checkDeadlines(response, started, lastReadNanos, download, "reading receipt content");
                output.write(buffer, 0, read);
            }
            output.flush();
        }

        if (total != receipt.getSizeBytes()) {
            throw new ReceiptIntegrityException("Receipt size does not match stored metadata");
        }
        String computed = HexFormat.of().formatHex(digest.digest());
        if (!computed.equalsIgnoreCase(receipt.getSha256Hex())) {
            throw new ReceiptIntegrityException("Receipt checksum does not match stored metadata");
        }
    }

    private void validateChecksum(TransferReceipt receipt, GetObjectResponse response) {
        String checksum = response.checksumSHA256();
        if (checksum == null || checksum.isBlank()) {
            throw new ReceiptIntegrityException("Receipt checksum is not available for validation");
        }
        String expected = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(receipt.getSha256Hex()));
        if (!expected.equals(checksum)) {
            throw new ReceiptIntegrityException("Receipt checksum does not match storage checksum");
        }
    }

    private void checkDeadlines(
            ResponseInputStream<GetObjectResponse> response,
            long started,
            long lastReadNanos,
            ReceiptS3Properties.Download download,
            String phase
    ) {
        Duration sinceStart = Duration.ofNanos(System.nanoTime() - started);
        if (sinceStart.compareTo(download.getTotalSoftDeadline()) > 0) {
            abort(response);
            throw new ReceiptIntegrityException("Receipt download exceeded total deadline while " + phase);
        }
        Duration sinceRead = Duration.ofNanos(System.nanoTime() - lastReadNanos);
        Duration readDeadline = lastReadNanos == started
                ? download.getResponseFirstReadTimeout()
                : download.getSocketReadTimeout();
        if (sinceRead.compareTo(readDeadline) > 0) {
            abort(response);
            throw new ReceiptIntegrityException("Receipt download timed out while " + phase);
        }
    }

    private void abort(ResponseInputStream<GetObjectResponse> response) {
        try {
            response.abort();
        } catch (Exception ignored) {
            // best effort abort
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private Path ensureReceiptsDir(ReceiptS3Properties.Download download) {
        Path baseDir = download.getTempDir().toAbsolutePath().normalize();
        Path receiptsDir = baseDir.resolve("receipts").normalize();
        if (!receiptsDir.startsWith(baseDir)) {
            throw new IllegalStateException("Invalid receipt temp directory");
        }
        try {
            Files.createDirectories(receiptsDir);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create receipt temp directory", ex);
        }
        return receiptsDir;
    }

    private Path createTempFile(Path receiptsDir) {
        try {
            Path file = Files.createTempFile(receiptsDir, "receipt-" + UUID.randomUUID(), ".tmp");
            hardenFile(file);
            return file;
        } catch (IOException ex) {
            throw new ReceiptIntegrityException("Receipt temp file could not be created");
        }
    }

    private void hardenFile(Path file) {
        ReceiptS3Properties.Download download = properties.getDownload();
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(file, perms);
            return;
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX, try ACL below
        } catch (IOException ex) {
            if (!download.isAllowUnverifiedTempAcl()) {
                throw new ReceiptIntegrityException("Receipt temp file permissions could not be hardened");
            }
            log.warn("Could not set POSIX permissions for receipt temp file {}", file, ex);
            return;
        }

        var view = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class);
        if (view == null) {
            if (!download.isAllowUnverifiedTempAcl()) {
                throw new ReceiptIntegrityException("Receipt temp file permissions could not be verified");
            }
            return;
        }
        try {
            var owner = view.getOwner();
            var lookup = file.getFileSystem().getUserPrincipalLookupService();
            var admins = lookup.lookupPrincipalByGroupName("Administrators");
            java.nio.file.attribute.GroupPrincipal system;
            try {
                system = lookup.lookupPrincipalByGroupName("SYSTEM");
            } catch (IOException ex) {
                system = lookup.lookupPrincipalByGroupName("SISTEMA");
            }
            var full = java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class);
            view.setAcl(java.util.List.of(
                    java.nio.file.attribute.AclEntry.newBuilder()
                            .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                            .setPrincipal(owner).setPermissions(full).build(),
                    java.nio.file.attribute.AclEntry.newBuilder()
                            .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                            .setPrincipal(admins).setPermissions(full).build(),
                    java.nio.file.attribute.AclEntry.newBuilder()
                            .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                            .setPrincipal(system).setPermissions(full).build()
            ));
        } catch (IOException | UnsupportedOperationException ex) {
            if (!download.isAllowUnverifiedTempAcl()) {
                throw new ReceiptIntegrityException("Receipt temp file permissions could not be hardened");
            }
            log.warn("Could not set ACL for receipt temp file {}", file, ex);
        }
    }
}
