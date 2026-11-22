package com.banco.transfer_service.transfer.receipt.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptS3ConfigTest {

    private static final String[] BASE_PROPERTIES = {
            "transfer.receipts.s3.bucket=banco-comprobantes",
            "transfer.receipts.s3.region=us-east-1"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ReceiptS3Config.class);

    @Test
    void createsS3ClientWithoutLocalEndpoint() {
        contextRunner.withPropertyValues(BASE_PROPERTIES).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(S3Client.class);
        });
    }

    @Test
    void createsS3ClientWithLocalEndpointAndStaticCredentials() {
        contextRunner.withPropertyValues(
                "transfer.receipts.s3.bucket=banco-comprobantes",
                "transfer.receipts.s3.region=us-east-1",
                "transfer.receipts.s3.endpoint=http://localhost:4566",
                "transfer.receipts.s3.access-key=test",
                "transfer.receipts.s3.secret-key=test"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(S3Client.class);
        });
    }

    @Test
    void failsFastWhenEndpointHasNoCredentials() {
        contextRunner.withPropertyValues(
                "transfer.receipts.s3.bucket=banco-comprobantes",
                "transfer.receipts.s3.region=us-east-1",
                "transfer.receipts.s3.endpoint=http://localhost:4566"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertRootFailure(context.getStartupFailure(), "access-key is required");
        });
    }

    @Test
    void failsFastOnUnsupportedChecksumMode() {
        contextRunner.withPropertyValues(
                "transfer.receipts.s3.bucket=banco-comprobantes",
                "transfer.receipts.s3.region=us-east-1",
                "transfer.receipts.s3.checksum-mode=DISABLED"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertRootFailure(context.getStartupFailure(), "checksum-mode must be ENABLED");
        });
    }

    @Test
    void failsFastWhenUploadSizeExceedsFiveMegabytes() {
        contextRunner.withPropertyValues(
                "transfer.receipts.s3.bucket=banco-comprobantes",
                "transfer.receipts.s3.region=us-east-1",
                "transfer.receipts.upload.max-file-size=6MB"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertRootFailure(context.getStartupFailure(), "cannot exceed 5MB");
        });
    }

    @Test
    void failsFastWhenHeartbeatIsNotShorterThanLease() {
        contextRunner.withPropertyValues(
                "transfer.receipts.s3.bucket=banco-comprobantes",
                "transfer.receipts.s3.region=us-east-1",
                "transfer.receipts.upload.lease-duration=30s",
                "transfer.receipts.upload.heartbeat-interval=30s"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertRootFailure(context.getStartupFailure(),
                    "heartbeat-interval must be shorter");
        });
    }

    private static void assertRootFailure(Throwable failure, String expectedMessage) {
        assertThat(failure).isNotNull();
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(IllegalStateException.class);
        assertThat(root.getMessage()).contains(expectedMessage);
    }
}
