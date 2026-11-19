package com.banco.account_service.account.web;

import com.banco.account_service.account.Status;
import com.banco.account_service.account.dto.AccountResponse;
import com.banco.account_service.account.service.AccountService;
import com.banco.account_service.ledger.dto.LedgerEntryResponse;
import com.banco.account_service.ledger.service.LedgerService;
import com.banco.account_service.security.AuthenticatedCustomer;
import com.banco.account_service.security.CustomerContext;
import com.banco.account_service.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AccountController.class, com.banco.account_service.ledger.web.LedgerController.class})
@Import({SecurityConfig.class, AuthenticatedCustomer.class})
class AccountApiSecurityTest {

    private static final String TOKEN = "signed-token";
    private static final String CORRELATION = "corr-web";
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final String NUMBER = "ES9121000418450200051332";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private AccountService accountService;

    @MockitoBean
    private LedgerService ledgerService;

    @Test
    void openAccount_withoutToken_returnsUnauthorizedProblem() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .header("X-Correlation-Id", CORRELATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + NUMBER + "\",\"currency\":\"EUR\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type")
                        .value("https://banco.local/problems/unauthorized"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION))
                .andExpect(header().string("X-Correlation-Id", CORRELATION));
    }

    @Test
    void openAccount_withoutWriteRole_returnsForbiddenProblem() throws Exception {
        authenticate(jwt(List.of("ACCOUNT_READ"), CUSTOMER_ID, null));

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("X-Correlation-Id", CORRELATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + NUMBER + "\",\"currency\":\"EUR\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type")
                        .value("https://banco.local/problems/forbidden"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION));
    }

    @Test
    void getAccount_withOnlyScope_returnsForbiddenProblem() throws Exception {
        authenticate(jwt(List.of(), CUSTOMER_ID, "account.read"));

        mockMvc.perform(get("/api/v1/accounts/" + ACCOUNT_ID)
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type")
                        .value("https://banco.local/problems/forbidden"));
    }

    @Test
    void openAccount_withWriteRole_createsAccountForJwtCustomer() throws Exception {
        authenticate(jwt(List.of("ACCOUNT_WRITE"), CUSTOMER_ID, null));
        when(accountService.openAccount(eq(CUSTOMER_ID), eq(NUMBER), eq("EUR")))
                .thenReturn(accountResponse());

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("X-Correlation-Id", CORRELATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + NUMBER + "\",\"currency\":\"EUR\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/accounts/" + ACCOUNT_ID))
                .andExpect(jsonPath("$.number").value(NUMBER))
                .andExpect(header().string("X-Correlation-Id", CORRELATION));
    }

    @Test
    void getAccount_asOwner_usesCustomerIdFromJwt() throws Exception {
        authenticate(jwt(List.of("ACCOUNT_READ"), CUSTOMER_ID, null));
        when(accountService.getAccountById(eq(ACCOUNT_ID), any(CustomerContext.class)))
                .thenReturn(accountResponse());

        mockMvc.perform(get("/api/v1/accounts/" + ACCOUNT_ID)
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ACCOUNT_ID.toString()));

        ArgumentCaptor<CustomerContext> customer = ArgumentCaptor.forClass(CustomerContext.class);
        verify(accountService).getAccountById(eq(ACCOUNT_ID), customer.capture());
        assertThat(customer.getValue().customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(customer.getValue().admin()).isFalse();
        assertThat(customer.getValue().internal()).isFalse();
    }

    @Test
    void getAccount_asAdmin_canReadAnyCustomer() throws Exception {
        authenticate(jwt(List.of("ADMIN"), CUSTOMER_ID, null));
        when(accountService.getAccountById(eq(ACCOUNT_ID), any(CustomerContext.class)))
                .thenReturn(accountResponse());

        mockMvc.perform(get("/api/v1/accounts/" + ACCOUNT_ID)
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());

        ArgumentCaptor<CustomerContext> customer = ArgumentCaptor.forClass(CustomerContext.class);
        verify(accountService).getAccountById(eq(ACCOUNT_ID), customer.capture());
        assertThat(customer.getValue().admin()).isTrue();
    }

    @Test
    void deposit_asInternalService_usesCustomerIdHeader() throws Exception {
        authenticate(jwt(List.of("ACCOUNT_INTERNAL"), null, null));
        when(ledgerService.deposit(
                eq(ACCOUNT_ID), any(BigDecimal.class), eq("DEP-1"), eq("idem-web"),
                any(CustomerContext.class)
        )).thenReturn(ledgerResponse());

        mockMvc.perform(post("/api/v1/accounts/" + ACCOUNT_ID + "/deposits")
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("X-Correlation-Id", CORRELATION)
                        .header("X-Customer-Id", CUSTOMER_ID.toString())
                        .header("Idempotency-Key", "idem-web")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"10.00\",\"reference\":\"DEP-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value("DEP-1"));

        ArgumentCaptor<CustomerContext> customer = ArgumentCaptor.forClass(CustomerContext.class);
        verify(ledgerService).deposit(
                eq(ACCOUNT_ID), any(BigDecimal.class), eq("DEP-1"), eq("idem-web"),
                customer.capture()
        );
        assertThat(customer.getValue().customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(customer.getValue().internal()).isTrue();
    }

    @Test
    void deposit_asInternalServiceWithoutCustomerIdHeader_returnsBadRequest() throws Exception {
        authenticate(jwt(List.of("ACCOUNT_INTERNAL"), null, null));

        mockMvc.perform(post("/api/v1/accounts/" + ACCOUNT_ID + "/deposits")
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("X-Correlation-Id", CORRELATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"10.00\",\"reference\":\"DEP-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value("https://banco.local/problems/invalid-argument"))
                .andExpect(jsonPath("$.detail")
                        .value("X-Customer-Id header is required for internal calls"));
    }

    @Test
    void openAccount_withInvalidBody_returnsValidationProblem() throws Exception {
        authenticate(jwt(List.of("ACCOUNT_WRITE"), CUSTOMER_ID, null));

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("X-Correlation-Id", CORRELATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"\",\"currency\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value("https://banco.local/problems/validation-error"))
                .andExpect(jsonPath("$.errors.number").value("number is required"))
                .andExpect(jsonPath("$.errors.currency").value("currency is required"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION));
    }

    @Test
    void getAccount_withMalformedUuid_returnsTypeMismatchProblem() throws Exception {
        authenticate(jwt(List.of("ACCOUNT_READ"), CUSTOMER_ID, null));

        mockMvc.perform(get("/api/v1/accounts/not-a-uuid")
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value("https://banco.local/problems/type-mismatch"))
                .andExpect(jsonPath("$.detail")
                        .value("Invalid value 'not-a-uuid' for parameter 'accountId'"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION));
    }

    @Test
    void getAccount_withoutReadRole_returnsForbiddenProblem() throws Exception {
        authenticate(jwt(List.of("ACCOUNT_WRITE"), CUSTOMER_ID, null));

        mockMvc.perform(get("/api/v1/accounts/" + ACCOUNT_ID)
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type")
                        .value("https://banco.local/problems/forbidden"));
    }

    private void authenticate(Jwt jwt) {
        when(jwtDecoder.decode(TOKEN)).thenReturn(jwt);
    }

    private Jwt jwt(List<String> roles, UUID customerId, String scope) {
        var builder = Jwt.withTokenValue(TOKEN)
                .header("alg", "none")
                .claim("sub", "test-user")
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (customerId != null) {
            builder.claim("customerId", customerId.toString());
        }
        if (scope != null) {
            builder.claim("scope", scope);
        }
        return builder.build();
    }

    private AccountResponse accountResponse() {
        return new AccountResponse(
                ACCOUNT_ID, NUMBER, "EUR", BigDecimal.ZERO, Status.ACTIVE, Instant.now()
        );
    }

    private LedgerEntryResponse ledgerResponse() {
        return new LedgerEntryResponse(
                UUID.randomUUID(), ACCOUNT_ID, com.banco.account_service.ledger.LedgerEntryType.CREDIT,
                new BigDecimal("10.00"), new BigDecimal("10.00"), "DEP-1", Instant.now()
        );
    }
}
