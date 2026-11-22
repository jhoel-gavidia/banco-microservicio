package com.banco.transfer_service.transfer.receipt.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
@EnableConfigurationProperties(ReceiptS3Properties.class)
public class ReceiptS3Config {

    @Bean
    S3Client receiptS3Client(ReceiptS3Properties properties) {
        ReceiptS3Properties.S3 s3 = properties.getS3();

        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(s3.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s3.isPathStyleAccessEnabled())
                        .build())
                // AWS SDK 2.59 removed the option to disable response checksum validation.
                .responseChecksumValidation(s3.isChecksumValidationEnabled()
                        ? ResponseChecksumValidation.WHEN_SUPPORTED
                        : ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(s3.getApiCallTimeout())
                        .apiCallAttemptTimeout(s3.getApiCallAttemptTimeout())
                        .build());

        if (properties.hasEndpoint()) {
            builder.endpointOverride(URI.create(s3.getEndpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey())
                    ));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }

        return builder.build();
    }
}
