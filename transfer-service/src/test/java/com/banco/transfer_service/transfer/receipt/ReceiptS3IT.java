package com.banco.transfer_service.transfer.receipt;

import com.banco.transfer_service.support.FlociS3IntegrationTest;
import com.banco.transfer_service.transfer.domain.Transfer;
import com.banco.transfer_service.transfer.domain.TransferStatus;
import com.banco.transfer_service.transfer.receipt.domain.ReceiptStatus;
import com.banco.transfer_service.transfer.receipt.domain.TransferReceipt;
import com.banco.transfer_service.transfer.receipt.repository.TransferReceiptRepository;
import com.banco.transfer_service.transfer.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ReceiptS3IT extends FlociS3IntegrationTest {

    private static final String TOKEN = "it-token";
    private static final UUID CUSTOMER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TransferRepository transferRepository;
    @Autowired
    private TransferReceiptRepository receiptRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanDb() {
        jdbcTemplate.execute("TRUNCATE TABLE transfer_receipts, outbox_events, idempotency_keys, transfers RESTART IDENTITY CASCADE");
        authenticate("transfer.write transfer.read", List.of("TRANSFER_WRITE", "TRANSFER_READ"));
        ensureBucket(); // idempotente
    }

    @Test
    void uploadAndDownload_withFloci_persistsActiveAndServesVerifiedFile() throws Exception {
        Transfer transfer = transferRepository.saveAndFlush(transfer(CUSTOMER_ID));
        byte[] pdf = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "comprobante.pdf", "application/pdf", pdf);

        MvcResult upload = mockMvc.perform(multipart("/api/v1/transfers/{id}/receipts", transfer.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.transferId").value(transfer.getId().toString()))
                .andExpect(jsonPath("$.customerId").value(CUSTOMER_ID.toString()))
                .andReturn();

        String body = upload.getResponse().getContentAsString();
        String receiptId = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        TransferReceipt receipt = receiptRepository.findById(UUID.fromString(receiptId)).orElseThrow();
        assertThat(receipt.getStatus()).isEqualTo(ReceiptStatus.ACTIVE);
        assertThat(receipt.getObjectKey()).contains(transfer.getId().toString());
        assertThat(receipt.getSha256Hex()).hasSize(64);

        mockMvc.perform(get("/api/v1/transfers/{id}/receipts", transfer.getId())
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(receiptId));

        byte[] downloaded = mockMvc.perform(get("/api/v1/transfers/{id}/receipts/{rid}/file", transfer.getId(), receiptId)
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(downloaded).isEqualTo(pdf);
    }

    @Test
    void download_whenStoredChecksumCorrupted_returnsIntegrityFailure() throws Exception {
        Transfer transfer = transferRepository.saveAndFlush(transfer(CUSTOMER_ID));
        byte[] pdf = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "ok.pdf", "application/pdf", pdf);

        MvcResult upload = mockMvc.perform(multipart("/api/v1/transfers/{id}/receipts", transfer.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isCreated())
                .andReturn();
        String receiptId = upload.getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        jdbcTemplate.update("UPDATE transfer_receipts SET sha256_hex = ? WHERE id = ?::uuid",
                "00".repeat(32), receiptId);

        mockMvc.perform(get("/api/v1/transfers/{id}/receipts/{rid}/file", transfer.getId(), receiptId)
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("https://banco.local/problems/receipt-integrity-failed"));
    }

    private void authenticate(String scope, List<String> roles) {
        Jwt jwt = Jwt.withTokenValue(TOKEN)
                .header("alg", "none")
                .claim("sub", "user")
                .claim("customerId", CUSTOMER_ID.toString())
                .claim("scope", scope)
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode(TOKEN)).thenReturn(jwt);
    }

    private static Transfer transfer(UUID customerId) {
        Transfer transfer = Transfer.initiate(
                customerId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                com.banco.transfer_service.transfer.domain.Money.of(new BigDecimal("10.00"), "USD"),
                "ref-it",
                "corr-it"
        );
        transfer.markDebited();
        transfer.markCompleting();
        transfer.markCompleted();
        return transfer;
    }
}
