package com.banco.transfer_service.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.net.URI;

public abstract class FlociS3IntegrationTest extends PostgresIntegrationTest {

    protected static final String BUCKET = "banco-comprobantes-local";
    protected static final String REGION = "us-east-1";
    protected static final String ACCESS_KEY = "test";
    protected static final String SECRET_KEY = "test";

    static final GenericContainer<?> FLOCI =
            new GenericContainer<>("floci/floci:2.5.0")
                    .withExposedPorts(4566)
                    .withEnv("FLOCI_DEFAULT_REGION", REGION)
                    .waitingFor(Wait.forHttp("/_floci/health").forPort(4566).forStatusCode(200));

    static {
        FLOCI.start();
        ensureBucket();
    }

    @DynamicPropertySource
    static void flociProperties(DynamicPropertyRegistry registry) {
        String endpoint = "http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566);
        registry.add("transfer.receipts.s3.bucket", () -> BUCKET);
        registry.add("transfer.receipts.s3.region", () -> REGION);
        registry.add("transfer.receipts.s3.endpoint", () -> endpoint);
        registry.add("transfer.receipts.s3.access-key", () -> ACCESS_KEY);
        registry.add("transfer.receipts.s3.secret-key", () -> SECRET_KEY);
        registry.add("transfer.receipts.s3.path-style-access-enabled", () -> "true");
        registry.add("transfer.receipts.s3.checksum-validation-enabled", () -> "true");
        registry.add("transfer.receipts.s3.checksum-mode", () -> "ENABLED");
    }

    protected static S3Client s3Client() {
        String endpoint = "http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566);
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .serviceConfiguration(c -> c.pathStyleAccessEnabled(true))
                .build();
    }

    protected static void ensureBucket() {
        try (S3Client client = s3Client()) {
            try {
                client.headBucket(HeadBucketRequest.builder().bucket(BUCKET).build());
            } catch (NoSuchBucketException ex) {
                client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
            } catch (software.amazon.awssdk.services.s3.model.S3Exception ex) {
                if (ex.statusCode() == 404) {
                    client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
                } else {
                    throw ex;
                }
            }
        }
    }
}
