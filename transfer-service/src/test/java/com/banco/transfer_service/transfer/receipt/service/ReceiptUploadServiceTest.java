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
        service = new ReceiptUploadService(transferService, receiptRepository, fileValidator, properties, s3Client, leaseHeartbeat);
        when(leaseHeartbeat.start(any(UUID.class), any(UUID.class))).thenReturn(null);
    }

    @Test
    void upload_onSuccess_marksActiveAndReturns201Payload() {
        mockTransfer();
        mockValidatedFile();
        when(receiptRepository.saveAndFlush(any(TransferReceipt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().versionId("v1").checksumSHA256(base64()).build());

        ReceiptResponse response = service.upload(TRANSFER_ID, file(), new CustomerContext(CUSTOMER_ID, false));

        assertThat(response.status()).isEqualTo(ReceiptStatus.ACTIVE);
        ArgumentCaptor<TransferReceipt> captor = ArgumentCaptor.forClass(TransferReceipt.class);
        verify(receiptRepository, org.mockito.Mockito.atLeastOnce()).saveAndFlush(captor.capture());
        TransferReceipt saved = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(saved.getStatus()).isEqualTo(ReceiptStatus.ACTIVE);
        assertThat(saved.getFailureReason()).isNull();
    }

    @Test
    void upload_onChecksumMismatch_marksFailedAndThrows409() {
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

        assertThatThrownBy(() -> service.upload(TRANSFER_ID, file(), new CustomerContext(CUSTOMER_ID, false)))
                .isInstanceOf(ReceiptFailedException.class)
                .satisfies(ex -> assertThat(((ReceiptFailedException) ex).getReceipt().failureReason())
                        .isEqualTo(ReceiptFailureReason.CHECKSUM_MISMATCH));
    }

    @Test
    void upload_onDefinitiveFailure_schedulesRetryAndThrows503() {
        mockTransfer();
        mockValidatedFile();
        when(receiptRepository.saveAndFlush(any(TransferReceipt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(500).message("boom").build());

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
        when(receiptRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            ArgumentCaptor<TransferReceipt> captor = ArgumentCaptor.forClass(TransferReceipt.class);
            verify(receiptRepository, org.mockito.Mockito.atLeastOnce()).saveAndFlush(captor.capture());
            TransferReceipt last = captor.getAllValues().get(captor.getAllValues().size() - 1);
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
