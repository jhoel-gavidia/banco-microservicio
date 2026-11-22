package com.banco.transfer_service.transfer.receipt.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "transfer.receipts")
public class ReceiptS3Properties {

    private static final DataSize MAX_UPLOAD_SIZE = DataSize.ofMegabytes(5);
    private static final long MIN_STREAM_BUFFER = 1024L;
    private static final long MAX_STREAM_BUFFER = 1024L * 1024L;

    private S3 s3 = new S3();
    private Upload upload = new Upload();
    private Download download = new Download();

    @PostConstruct
    void validate() {
        validateS3();
        validateUpload();
        validateDownload();
    }

    public boolean hasEndpoint() {
        return s3 != null && hasText(s3.endpoint);
    }

    private void validateS3() {
        require(s3 != null, "s3 configuration is required");
        require(hasText(s3.bucket), "s3.bucket is required");
        require(hasText(s3.region), "s3.region is required");
        require(positive(s3.apiCallTimeout), "s3.api-call-timeout must be positive");
        require(positive(s3.apiCallAttemptTimeout), "s3.api-call-attempt-timeout must be positive");
        require(hasText(s3.checksumMode), "s3.checksum-mode is required");
        require("ENABLED".equalsIgnoreCase(s3.checksumMode.trim()),
                "s3.checksum-mode must be ENABLED");

        if (hasEndpoint()) {
            require(hasText(s3.accessKey),
                    "s3.access-key is required when an endpoint is configured");
            require(hasText(s3.secretKey),
                    "s3.secret-key is required when an endpoint is configured");
            try {
                URI endpoint = URI.create(s3.endpoint);
                require(endpoint.getScheme() != null && endpoint.getHost() != null,
                        "s3.endpoint must be an absolute URI");
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(prefix("s3.endpoint is invalid"), exception);
            }
        }
    }

    private void validateUpload() {
        require(upload != null, "upload configuration is required");
        require(positive(upload.maxFileSize), "upload.max-file-size must be positive");
        require(upload.maxFileSize.toBytes() <= MAX_UPLOAD_SIZE.toBytes(),
                "upload.max-file-size cannot exceed 5MB");
        require(upload.maxAttempts >= 1, "upload.max-attempts must be at least 1");
        require(positive(upload.leaseDuration), "upload.lease-duration must be positive");
        require(positive(upload.heartbeatInterval), "upload.heartbeat-interval must be positive");
        require(upload.heartbeatInterval.compareTo(upload.leaseDuration) < 0,
                "upload.heartbeat-interval must be shorter than upload.lease-duration");
        require(positive(upload.retryBackoff), "upload.retry-backoff must be positive");
        require(positive(upload.missingGrace), "upload.missing-grace must be positive");
    }

    private void validateDownload() {
        require(download != null, "download configuration is required");
        require(download.maxConcurrent >= 1, "download.max-concurrent must be at least 1");
        require(download.tempDir != null, "download.temp-dir is required");
        require(positive(download.socketReadTimeout),
                "download.socket-read-timeout must be positive");
        require(positive(download.responseFirstReadTimeout),
                "download.response-first-read-timeout must be positive");
        require(positive(download.totalSoftDeadline),
                "download.total-soft-deadline must be positive");
        require(download.responseFirstReadTimeout.compareTo(download.totalSoftDeadline) <= 0,
                "download.response-first-read-timeout cannot exceed download.total-soft-deadline");
        require(positive(download.tempTtl), "download.temp-ttl must be positive");
        require(positive(download.streamBufferSize), "download.stream-buffer-size must be positive");
        long bufferBytes = download.streamBufferSize.toBytes();
        require(bufferBytes >= MIN_STREAM_BUFFER && bufferBytes <= MAX_STREAM_BUFFER,
                "download.stream-buffer-size must be between 1KB and 1MB");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(prefix(message));
        }
    }

    private static String prefix(String message) {
        return "Invalid transfer.receipts configuration: " + message;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private static boolean positive(DataSize size) {
        return size != null && size.toBytes() > 0;
    }

    @Getter
    @Setter
    public static class S3 {

        private String bucket;
        private String region;
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private boolean pathStyleAccessEnabled = true;
        private boolean checksumValidationEnabled = true;
        private String checksumMode = "ENABLED";
        private Duration apiCallTimeout = Duration.ofSeconds(10);
        private Duration apiCallAttemptTimeout = Duration.ofSeconds(3);
    }

    @Getter
    @Setter
    public static class Upload {

        private DataSize maxFileSize = DataSize.ofMegabytes(5);
        private int maxAttempts = 3;
        private Duration leaseDuration = Duration.ofMinutes(2);
        private Duration heartbeatInterval = Duration.ofSeconds(30);
        private Duration retryBackoff = Duration.ofSeconds(5);
        private Duration missingGrace = Duration.ofMinutes(2);
    }

    @Getter
    @Setter
    public static class Download {

        private int maxConcurrent = 4;
        private Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        private Duration socketReadTimeout = Duration.ofSeconds(3);
        private Duration responseFirstReadTimeout = Duration.ofSeconds(10);
        private Duration totalSoftDeadline = Duration.ofSeconds(30);
        private DataSize streamBufferSize = DataSize.ofKilobytes(8);
        private Duration tempTtl = Duration.ofHours(1);
        private boolean allowUnverifiedTempAcl;
    }
}
