package com.banco.transfer_service.security;

import com.banco.transfer_service.transfer.domain.TransferStatus;
import com.banco.transfer_service.transfer.dto.TransferResponse;
import com.banco.transfer_service.transfer.service.TransferService;
import com.banco.transfer_service.transfer.web.TransferController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthenticatedCustomerTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();

    private final AuthenticatedCustomer authenticatedCustomer = new AuthenticatedCustomer();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void current_withCustomerIdClaim_returnsCustomerContext() {
        authenticate(jwtWithCustomerId(CUSTOMER_ID), false);

        CustomerContext customer = authenticatedCustomer.current();

        assertThat(customer.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(customer.admin()).isFalse();
    }

    @Test
    void current_withAdminRole_marksAdmin() {
        authenticate(jwtWithCustomerId(CUSTOMER_ID), true);

        CustomerContext customer = authenticatedCustomer.current();

        assertThat(customer.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(customer.admin()).isTrue();
    }

    @Test
    void current_withoutClaim_throwsIllegalState() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "user")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_TRANSFER_READ")))
        );

        assertThatThrownBy(authenticatedCustomer::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing the customerId claim");
    }

    @Test
    void current_withoutAuthentication_throwsIllegalState() {
        assertThatThrownBy(authenticatedCustomer::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No authenticated JWT");
    }

    private void authenticate(Jwt jwt, boolean admin) {
        var authorities = List.of(new SimpleGrantedAuthority(
                admin ? "ROLE_ADMIN" : "ROLE_TRANSFER_READ"
        ));
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, authorities)
        );
    }

    private Jwt jwtWithCustomerId(UUID customerId) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "customer")
                .claim("customerId", customerId.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}

@WebMvcTest(TransferController.class)
@Import({SecurityConfig.class, AuthenticatedCustomer.class})
class TransferApiSecurityTest {

    private static final String TOKEN = "signed-token";
    private static final String CORRELATION = "corr-transfer-web";
    private static final UUID WEB_CUSTOMER_ID = UUID.randomUUID();
    private static final UUID TRANSFER_ID = UUID.randomUUID();
    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private TransferService transferService;

    @Test
    void create_withoutToken_returnsUnauthorizedProblem() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .header("X-Correlation-Id", CORRELATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type")
                        .value("https://banco.local/problems/unauthorized"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION))
                .andExpect(header().string("X-Correlation-Id", CORRELATION));
    }

    @Test
    void create_withScopeButWithoutRole_returnsForbiddenProblem() throws Exception {
        authenticate("transfer.write", List.of(), true);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("X-Correlation-Id", CORRELATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type")
                        .value("https://banco.local/problems/forbidden"));
    }

    @Test
    void create_withRoleButWithoutScope_returnsForbiddenProblem() throws Exception {
        authenticate(null, List.of("TRANSFER_WRITE"), true);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("X-Correlation-Id", CORRELATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type")
                        .value("https://banco.local/problems/forbidden"));
    }

    @Test
    void create_withRequiredScopeAndRole_createsForJwtCustomer() throws Exception {
        authenticate("transfer.write", List.of("TRANSFER_WRITE"), true);
        when(transferService.create(any(), any(CustomerContext.class), eq("idem-web")))
                .thenReturn(response());

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("X-Correlation-Id", CORRELATION)
                        .header("Idempotency-Key", "idem-web")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/transfers/" + TRANSFER_ID))
                .andExpect(jsonPath("$.reference").value("TRX-WEB"));

        ArgumentCaptor<CustomerContext> customer = ArgumentCaptor.forClass(CustomerContext.class);
        verify(transferService).create(any(), customer.capture(), eq("idem-web"));
        assertThat(customer.getValue().customerId()).isEqualTo(WEB_CUSTOMER_ID);
        assertThat(customer.getValue().admin()).isFalse();
    }

    @Test
    void getById_asOwner_usesCustomerIdFromJwt() throws Exception {
        authenticate("transfer.read", List.of("TRANSFER_READ"), true);
        when(transferService.getById(eq(TRANSFER_ID), any(CustomerContext.class)))
                .thenReturn(response());

        mockMvc.perform(get("/api/v1/transfers/" + TRANSFER_ID)
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(TRANSFER_ID.toString()));

        ArgumentCaptor<CustomerContext> customer = ArgumentCaptor.forClass(CustomerContext.class);
        verify(transferService).getById(eq(TRANSFER_ID), customer.capture());
        assertThat(customer.getValue().customerId()).isEqualTo(WEB_CUSTOMER_ID);
        assertThat(customer.getValue().admin()).isFalse();
    }

    @Test
    void list_asAdmin_usesAdminContext() throws Exception {
        authenticate("transfer.read", List.of("TRANSFER_READ", "ADMIN"), true);
        when(transferService.list(any(CustomerContext.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/transfers")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());

        ArgumentCaptor<CustomerContext> customer = ArgumentCaptor.forClass(CustomerContext.class);
        verify(transferService).list(customer.capture());
        assertThat(customer.getValue().admin()).isTrue();
    }

    @Test
    void create_withInvalidBody_returnsValidationProblem() throws Exception {
        authenticate("transfer.write", List.of("TRANSFER_WRITE"), true);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("X-Correlation-Id", CORRELATION)
                        .header("Idempotency-Key", "idem-web")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":null,\"targetAccountId\":null,"
                                + "\"amount\":null,\"currency\":\"\",\"reference\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value("https://banco.local/problems/validation-error"))
                .andExpect(jsonPath("$.errors.sourceAccountId")
                        .value("sourceAccountId is required"))
                .andExpect(jsonPath("$.errors.currency").value("currency is required"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION));
    }

    @Test
    void create_withoutIdempotencyKey_returnsInvalidArgumentProblem() throws Exception {
        authenticate("transfer.write", List.of("TRANSFER_WRITE"), true);
        when(transferService.create(any(), any(CustomerContext.class), any()))
                .thenThrow(new IllegalArgumentException("Idempotency-Key header is required"));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("X-Correlation-Id", CORRELATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value("https://banco.local/problems/invalid-argument"))
                .andExpect(jsonPath("$.detail")
                        .value("Idempotency-Key header is required"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION));
    }

    @Test
    void getById_withMalformedUuid_returnsTypeMismatchProblem() throws Exception {
        authenticate("transfer.read", List.of("TRANSFER_READ"), true);

        mockMvc.perform(get("/api/v1/transfers/not-a-uuid")
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value("https://banco.local/problems/type-mismatch"))
                .andExpect(jsonPath("$.detail")
                        .value("Invalid value 'not-a-uuid' for parameter 'id'"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION));
    }

    private void authenticate(String scope, List<String> roles, boolean withCustomerId) {
        var builder = Jwt.withTokenValue(TOKEN)
                .header("alg", "none")
                .claim("sub", "test-user")
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (scope != null) {
            builder.claim("scope", scope);
        }
        if (withCustomerId) {
            builder.claim("customerId", WEB_CUSTOMER_ID.toString());
        }
        when(jwtDecoder.decode(TOKEN)).thenReturn(builder.build());
    }

    private String validBody() {
        return "{\"sourceAccountId\":\"" + SOURCE + "\",\"targetAccountId\":\"" + TARGET
                + "\",\"amount\":\"25.00\",\"currency\":\"EUR\",\"reference\":\"TRX-WEB\"}";
    }

    private TransferResponse response() {
        return new TransferResponse(
                TRANSFER_ID, WEB_CUSTOMER_ID, SOURCE, TARGET, new BigDecimal("25.00"), "EUR",
                TransferStatus.PENDING, "TRX-WEB", Instant.now()
        );
    }
}
