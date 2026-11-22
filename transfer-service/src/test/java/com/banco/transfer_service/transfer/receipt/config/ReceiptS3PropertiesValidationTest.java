package com.banco.transfer_service.transfer.receipt.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptS3PropertiesValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "transfer.receipts.s3.bucket=banco-comprobantes",
                    "transfer.receipts.s3.region=us-east-1",
                    "transfer.receipts.s3.checksum-mode=ENABLED",
                    "transfer.receipts.upload.max-attempts=3",
                    "transfer.receipts.upload.lease-duration=2m",
                    "transfer.receipts.upload.heartbeat-interval=30s",
                    "transfer.receipts.upload.retry-backoff=5s",
                    "transfer.receipts.upload.missing-grace=3m"
            );

    @Test
    void validConfig_starts() {
        contextRunner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void invalidAttemptTimeout_fails() {
        contextRunner.withPropertyValues(
                "transfer.receipts.s3.api-call-timeout=2s",
                "transfer.receipts.s3.api-call-attempt-timeout=3s"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void missingGraceShorterThanLease_fails() {
        contextRunner.withPropertyValues("transfer.receipts.upload.missing-grace=1m")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void heartbeatNotHalfOfLease_fails() {
        contextRunner.withPropertyValues("transfer.receipts.upload.heartbeat-interval=70s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void maxAttemptsOutOfRange_fails() {
        contextRunner.withPropertyValues("transfer.receipts.upload.max-attempts=4")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(ReceiptS3Properties.class)
    static class TestConfig {
    }
}
