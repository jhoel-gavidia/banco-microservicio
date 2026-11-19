package com.banco.account_service.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedCustomerTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();

    private final AuthenticatedCustomer authenticatedCustomer = new AuthenticatedCustomer();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void current_withCustomerJwt_usesCustomerIdClaim() {
        authenticate(jwtWithCustomerId(CUSTOMER_ID), "ROLE_ACCOUNT_READ");

        CustomerContext customer = authenticatedCustomer.current();

        assertThat(customer.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(customer.admin()).isFalse();
        assertThat(customer.internal()).isFalse();
    }

    @Test
    void current_withAdminJwt_marksAdmin() {
        authenticate(jwtWithCustomerId(CUSTOMER_ID), "ROLE_ADMIN");

        CustomerContext customer = authenticatedCustomer.current();

        assertThat(customer.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(customer.admin()).isTrue();
        assertThat(customer.internal()).isFalse();
    }

    @Test
    void current_withInternalJwt_usesCustomerIdHeader() {
        authenticate(jwtWithoutCustomerId(), "ROLE_ACCOUNT_INTERNAL");
        setRequestWithCustomerId(CUSTOMER_ID.toString());

        CustomerContext customer = authenticatedCustomer.current();

        assertThat(customer.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(customer.admin()).isFalse();
        assertThat(customer.internal()).isTrue();
    }

    @Test
    void current_withInternalJwtAndNoHeader_throwsIllegalArgument() {
        authenticate(jwtWithoutCustomerId(), "ROLE_ACCOUNT_INTERNAL");
        setRequestWithCustomerId(null);

        assertThatThrownBy(authenticatedCustomer::current)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("X-Customer-Id header is required");
    }

    @Test
    void current_withInternalJwtAndInvalidHeader_throwsIllegalArgument() {
        authenticate(jwtWithoutCustomerId(), "ROLE_ACCOUNT_INTERNAL");
        setRequestWithCustomerId("not-a-uuid");

        assertThatThrownBy(authenticatedCustomer::current)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a valid UUID");
    }

    @Test
    void current_withJwtMissingCustomerId_throwsIllegalState() {
        authenticate(jwtWithoutCustomerId(), "ROLE_ACCOUNT_READ");

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

    private void authenticate(Jwt jwt, String... authorities) {
        var granted = List.of(authorities).stream()
                .map(SimpleGrantedAuthority::new)
                .map(authority -> (org.springframework.security.core.GrantedAuthority) authority)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, granted)
        );
    }

    private void setRequestWithCustomerId(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (value != null) {
            request.addHeader("X-Customer-Id", value);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
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

    private Jwt jwtWithoutCustomerId() {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "internal")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
