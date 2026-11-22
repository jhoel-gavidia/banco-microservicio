package com.banco.transfer_service.transfer.receipt.service;

import com.banco.transfer_service.security.CustomerContext;
import com.banco.transfer_service.transfer.domain.TransferStatus;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptUploadServiceTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID TRANSFER_ID = UUID.randomUUID();

    @Mock
    private TransferService transferService;
    @Mock
    private TransferReceiptRepository receiptRepository;
    @Mock
    private ReceiptFileValidator fileValidator;
    @Mock
    private S3Client s3Client;
    @Mock
    private ReceiptLeaseHeartbeat leaseHeartbeat;

    private ReceiptS3Properties properties;
    private ReceiptUploadService service;

    @BeforeEach
    void setUp() {
        properties = new ReceiptS3Properties();
        properties.getS3().setBucket("banco-comprobantes-local");
        properties.getS3().setRegion("us-east-1");
        properties.getUpload().setLeaseDuration(java.time.Duration.ofMinutes(2));
        properties.getUpload().setRetryBackoff(java.time.Duration.ofSeconds(5));
        TransactionTemplate tx = new TransactionTemplate();
        tx.setTransactionManager(new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() { return new Object(); }
            @Override
            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }
            @Override
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { }
            @Override
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { }
        });
        service = new ReceiptUploadService(transferService, receiptRepository, fileValidator, properties, s3Client, leaseHeartbeat, tx);
        when(leaseHeartbeat.start(any(UUID.class), any(UUID.class))).thenReturn(null);
    }

    @Test
    void upload_onSuccess_marksActiveAndReturns201Payload() {
        mockTransfer();
        mockValidatedFile();
        when(receiptRepository.saveAndFlush(any(TransferReceipt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().versionId("v1").checksumSHA256(base64()).build());
        when(receiptRepository.markActive(any(UUID.class), any(UUID.class), any())).thenReturn(1);
        when(receiptRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            ArgumentCaptor<TransferReceipt> captor = ArgumentCaptor.forClass(TransferReceipt.class);
            verify(receiptRepository, org.mockito.Mockito.atLeastOnce()).saveAndFlush(captor.capture());
            TransferReceipt saved = captor.getAllValues().get(captor.getAllValues().size() - 1);
            saved.markActive("v1", Instant.now());
            return Optional.of(saved);
        });

        ReceiptResponse response = service.upload(TRANSFER_ID, file(), new CustomerContext(CUSTOMER_ID, false));

        assertThat(response.status()).isEqualTo(ReceiptStatus.ACTIVE);
        verify(receiptRepository).markActive(any(UUID.class), any(UUID.class), any());
    }

    @Test
    void upload_onChecksumMismatchWithoutVerification_releasesLeaseAndThrows202() {
        mockTransfer();
        mockValidatedFile();
        when(receiptRepository.saveAndFlush(any(TransferReceipt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(400)
                        .awsErrorDetails(AwsErrorDetails.builder()
                                .errorCode("BadDigest")
                                .errorMessage("checksum mismatch")
                                .build())
                        .build());
        when(s3Client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenReturn(software.amazon.awssdk.services.s3.model.HeadObjectResponse.builder().build());
        when(receiptRepository.releaseAmbiguousLease(any(UUID.class), any(UUID.class), any(Long.class))).thenReturn(1);
        when(receiptRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            ArgumentCaptor<TransferReceipt> captor = ArgumentCaptor.forClass(TransferReceipt.class);
            verify(receiptRepository, org.mockito.Mockito.atLeastOnce()).saveAndFlush(captor.capture());
            TransferReceipt last = captor.getAllValues().get(captor.getAllValues().size() - 1);
            last.releaseAmbiguousLease(Instant.now().plusSeconds(5));
            return Optional.of(last);
        });

        assertThatThrownBy(() -> service.upload(TRANSFER_ID, file(), new CustomerContext(CUSTOMER_ID, false)))
                .isInstanceOf(ReceiptUploadPendingException.class)
                .satisfies(ex -> assertThat(((ReceiptUploadPendingException) ex).getReceipt().status())
                        .isEqualTo(ReceiptStatus.PENDING));
    }

    @Test
    void upload_onChecksumMismatchWithDifferentHeadChecksum_marksFailedAndThrows409() {
        mockTransfer();
        mockValidatedFile();
        when(receiptRepository.saveAndFlush(any(TransferReceipt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(400)
                        .awsErrorDetails(AwsErrorDetails.builder()
                                .errorCode("BadDigest")
                                .errorMessage("checksum mismatch")
                                .build())
                        .build());
        when(s3Client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenReturn(software.amazon.awssdk.services.s3.model.HeadObjectResponse.builder()
                        .checksumSHA256(Base64.getEncoder().encodeToString(new byte[32]))
                        .build());
        when(receiptRepository.markFailed(any(UUID.class), any(UUID.class), any(), any())).thenReturn(1);
        when(receiptRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            ArgumentCaptor<TransferReceipt> c = ArgumentCaptor.forClass(TransferReceipt.class);
            verify(receiptRepository, org.mockito.Mockito.atLeastOnce()).saveAndFlush(c.capture());
            TransferReceipt saved = c.getAllValues().get(c.getAllValues().size() - 1);
            saved.markFailed(ReceiptFailureReason.CHECKSUM_MISMATCH, "checksum mismatch");
            return Optional.of(saved);
        });

        assertThatThrownBy(() -> service.upload(TRANSFER_ID, file(), new CustomerContext(CUSTOMER_ID, false)))
                .isInstanceOf(ReceiptFailedException.class)
                .satisfies(ex -> assertThat(((ReceiptFailedException) ex).getReceipt().failureReason())
                        .isEqualTo(ReceiptFailureReason.CHECKSUM_MISMATCH));
    }

    @Test
    void upload_onS3ServerError_releasesLeaseAndThrows202() {
        mockTransfer();
        mockValidatedFile();
        when(receiptRepository.saveAndFlush(any(TransferReceipt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(500).message("boom").build());
        when(receiptRepository.releaseAmbiguousLease(any(UUID.class), any(UUID.class), any(Long.class))).thenReturn(1);
        when(receiptRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            ArgumentCaptor<TransferReceipt> c = ArgumentCaptor.forClass(TransferReceipt.class);
            verify(receiptRepository, org.mockito.Mockito.atLeastOnce()).saveAndFlush(c.capture());
            TransferReceipt saved = c.getAllValues().get(c.getAllValues().size() - 1);
            saved.releaseAmbiguousLease(Instant.now().plusSeconds(5));
            return Optional.of(saved);
        });

        assertThatThrownBy(() -> service.upload(TRANSFER_ID, file(), new CustomerContext(CUSTOMER_ID, false)))
                .isInstanceOf(ReceiptUploadPendingException.class)
                .satisfies(ex -> assertThat(((ReceiptUploadPendingException) ex).getReceipt().status())
                        .isEqualTo(ReceiptStatus.PENDING));
    }

    @Test
    void upload_onDefinitive4xx_schedulesRetryAndThrows503() {
        mockTransfer();
        mockValidatedFile();
        when(receiptRepository.saveAndFlush(any(TransferReceipt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(403)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").errorMessage("denied").build())
                        .build());
        when(receiptRepository.reschedule(any(UUID.class), any(UUID.class), any(Long.class), any())).thenReturn(1);
        when(receiptRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            ArgumentCaptor<TransferReceipt> c = ArgumentCaptor.forClass(TransferReceipt.class);
            verify(receiptRepository, org.mockito.Mockito.atLeastOnce()).saveAndFlush(c.capture());
            TransferReceipt saved = c.getAllValues().get(c.getAllValues().size() - 1);
            saved.scheduleRetry(Instant.now().plusSeconds(5), "denied");
            return Optional.of(saved);
        });

        assertThatThrownBy(() -> service.upload(TRANSFER_ID, file(), new CustomerContext(CUSTOMER_ID, false)))
                .isInstanceOf(ReceiptStorageUnavailableException.class);
    }

    @Test
    void upload_onAmbiguousTimeout_releasesLeaseAndReturns202() {
        mockTransfer();
        mockValidatedFile();
        when(receiptRepository.saveAndFlush(any(TransferReceipt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(ApiCallTimeoutException.create(1000));
        when(receiptRepository.releaseAmbiguousLease(any(UUID.class), any(UUID.class), any(Long.class))).thenReturn(1);
        when(receiptRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            ArgumentCaptor<TransferReceipt> captor = ArgumentCaptor.forClass(TransferReceipt.class);
            verify(receiptRepository, org.mockito.Mockito.atLeastOnce()).saveAndFlush(captor.capture());
            TransferReceipt last = captor.getAllValues().get(captor.getAllValues().size() - 1);
            last.releaseAmbiguousLease(Instant.now().plusSeconds(5));
            return Optional.of(last);
        });

        assertThatThrownBy(() -> service.upload(TRANSFER_ID, file(), new CustomerContext(CUSTOMER_ID, false)))
                .isInstanceOf(ReceiptUploadPendingException.class)
                .satisfies(ex -> {
                    ReceiptResponse receipt = ((ReceiptUploadPendingException) ex).getReceipt();
                    assertThat(receipt.status()).isEqualTo(ReceiptStatus.PENDING);
                });
    }

    private void mockTransfer() {
        when(transferService.getById(TRANSFER_ID, new CustomerContext(CUSTOMER_ID, false)))
                .thenReturn(new TransferResponse(TRANSFER_ID, CUSTOMER_ID, UUID.randomUUID(), UUID.randomUUID(),
                        new BigDecimal("10.00"), "USD", TransferStatus.COMPLETED, "ref", Instant.now()));
    }

    private void mockValidatedFile() {
        when(fileValidator.validate(any(MultipartFile.class), any(Long.class)))
                .thenReturn(new ReceiptFileValidator.ValidatedReceiptFile(
                        "11".repeat(32), "application/pdf", "comprobante.pdf", 5
                ));
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "comprobante.pdf", "application/pdf", "%PDF-".getBytes());
    }

    private static String base64() {
        return java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex("11".repeat(32)));
    }
}
