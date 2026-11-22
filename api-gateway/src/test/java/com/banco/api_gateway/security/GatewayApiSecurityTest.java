package com.banco.api_gateway.security;

import com.banco.api_gateway.error.GatewayProblemHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@Import({SecurityConfig.class, GatewayProblemHandler.class, GatewayApiSecurityTest.ProbeController.class})
class GatewayApiSecurityTest {

    private static final String TOKEN = "signed-token";
    private static final String CORRELATION = "corr-gateway-web";
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void accountWithoutToken_returnsUnauthorizedProblem() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{id}", ACCOUNT_ID)
                        .header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type")
                        .value("https://banco.local/problems/unauthorized"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION))
                .andExpect(header().string("X-Correlation-Id", CORRELATION));
    }

    @Test
    void accountWithAccountReadRole_isAllowed() throws Exception {
        authenticate(null, List.of("ACCOUNT_READ"));

        mockMvc.perform(get("/api/v1/accounts/{id}", ACCOUNT_ID)
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string("account-ok"));
    }

    @Test
    void transferWithScopeButWithoutRole_returnsForbiddenProblem() throws Exception {
        authenticate("transfer.read", List.of());

        mockMvc.perform(get("/api/v1/transfers")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type")
                        .value("https://banco.local/problems/forbidden"));
    }

    @Test
    void transferWithRoleButWithoutScope_returnsForbiddenProblem() throws Exception {
        authenticate(null, List.of("TRANSFER_READ"));

        mockMvc.perform(get("/api/v1/transfers")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type")
                        .value("https://banco.local/problems/forbidden"));
    }

    @Test
    void transferWithScopeAndRole_isAllowed() throws Exception {
        authenticate("transfer.read", List.of("TRANSFER_READ"));

        mockMvc.perform(get("/api/v1/transfers")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string("transfers-ok"));
    }

    @Test
    void createTransferWithWriteScopeAndRole_isAllowed() throws Exception {
        authenticate("transfer.write", List.of("TRANSFER_WRITE"));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(content().string("transfer-created"));
    }

    @Test
    void unmappedRouteWithValidToken_returnsNotFoundProblem() throws Exception {
        authenticate(null, List.of("ACCOUNT_READ"));

        mockMvc.perform(get("/api/v1/unknown")
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type")
                        .value("https://banco.local/problems/not-found"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION));
    }



    private void authenticate(String scope, List<String> roles) {
        var builder = Jwt.withTokenValue(TOKEN)
                .header("alg", "none")
                .claim("sub", "gateway-test")
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (scope != null) {
            builder.claim("scope", scope);
        }
        when(jwtDecoder.decode(TOKEN)).thenReturn(builder.build());
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/accounts/{id}")
        String account(@PathVariable UUID id) {
            return "account-ok";
        }

        @GetMapping("/api/v1/transfers")
        String transfers() {
            return "transfers-ok";
        }

        @PostMapping("/api/v1/transfers")
        org.springframework.http.ResponseEntity<String> createTransfer() {
            return org.springframework.http.ResponseEntity.created(
                    java.net.URI.create("/api/v1/transfers/1")
            ).body("transfer-created");
        }
    }
}