package com.banco.transfer_service.transfer.receipt.web;

import com.banco.transfer_service.security.AuthenticatedCustomer;
import com.banco.transfer_service.security.CustomerContext;
import com.banco.transfer_service.security.SecurityConfig;
import com.banco.transfer_service.transfer.receipt.domain.ReceiptStatus;
import com.banco.transfer_service.transfer.receipt.domain.TransferReceipt;
import com.banco.transfer_service.transfer.receipt.dto.ReceiptResponse;
import com.banco.transfer_service.transfer.receipt.service.ReceiptFile;
import com.banco.transfer_service.transfer.receipt.service.ReceiptQueryService;
import com.banco.transfer_service.transfer.receipt.service.ReceiptStorageService;
import com.banco.transfer_service.transfer.receipt.service.ReceiptUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReceiptController.class)
@Import({SecurityConfig.class, AuthenticatedCustomer.class})
class ReceiptControllerSecurityTest {

    private static final String TOKEN = "signed-token";
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID TRANSFER_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private ReceiptUploadService receiptUploadService;

    @MockitoBean
    private ReceiptQueryService receiptQueryService;

    @MockitoBean
    private ReceiptStorageService receiptStorageService;

    @Test
    void upload_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(multipart("/api/v1/transfers/{id}/receipts", TRANSFER_ID).file(file()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://banco.local/problems/unauthorized"));
    }

    @Test
    void upload_withReadScopeOnly_returnsForbidden() throws Exception {
        authenticate("transfer.read", List.of("TRANSFER_READ"));

        mockMvc.perform(multipart("/api/v1/transfers/{id}/receipts", TRANSFER_ID)
                        .file(file())
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://banco.local/problems/forbidden"));
    }

    @Test
    void upload_withWriteScopeAndRole_returnsCreated() throws Exception {
        authenticate("transfer.write", List.of("TRANSFER_WRITE"));
        UUID receiptId = UUID.randomUUID();
        when(receiptUploadService.upload(eq(TRANSFER_ID), any(), any(CustomerContext.class)))
                .thenReturn(new ReceiptResponse(receiptId, TRANSFER_ID, CUSTOMER_ID, ReceiptStatus.ACTIVE,
                        "application/pdf", "receipt.pdf", 10, "11".repeat(32), Instant.now(), Instant.now(), null));

        mockMvc.perform(multipart("/api/v1/transfers/{id}/receipts", TRANSFER_ID)
                        .file(file())
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(receiptId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void list_withReadScopeAndRole_returnsOk() throws Exception {
        authenticate("transfer.read", List.of("TRANSFER_READ"));
        when(receiptQueryService.list(eq(TRANSFER_ID), any(CustomerContext.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/transfers/{id}/receipts", TRANSFER_ID)
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void download_withReadScopeAndRole_returnsFileHeaders() throws Exception {
        authenticate("transfer.read", List.of("TRANSFER_READ"));
        UUID receiptId = UUID.randomUUID();
        TransferReceipt receipt = TransferReceipt.pending(receiptId, TRANSFER_ID, CUSTOMER_ID, "11".repeat(32),
                "transfers/t/r/file.pdf", "application/pdf", "file.pdf", 4, "bucket",
                UUID.randomUUID(), Instant.now().plusSeconds(60));
        receipt.markActive("v1", Instant.now());
        when(receiptQueryService.getActive(eq(TRANSFER_ID), eq(receiptId), any(CustomerContext.class)))
                .thenReturn(receipt);
        when(receiptStorageService.download(receipt)).thenReturn(new ReceiptFile(
                new ByteArrayInputStream(new byte[] {1, 2, 3, 4}), "application/pdf", "file.pdf", 4, () -> {}));

        mockMvc.perform(get("/api/v1/transfers/{id}/receipts/{receiptId}/file", TRANSFER_ID, receiptId)
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    private void authenticate(String scope, List<String> roles) {
        var builder = Jwt.withTokenValue(TOKEN)
                .header("alg", "none")
                .claim("sub", "user")
                .claim("customerId", CUSTOMER_ID.toString())
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (scope != null) {
            builder.claim("scope", scope);
        }
        when(jwtDecoder.decode(TOKEN)).thenReturn(builder.build());
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "receipt.pdf", "application/pdf", "%PDF-1.4".getBytes());
    }
}
