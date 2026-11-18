package com.banco.transfer_service.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AuthenticatedCustomer {

    private static final String ADMIN_ROLE = "ROLE_ADMIN";

    public CustomerContext current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("No authenticated JWT in the security context");
        }

        String claim = jwt.getClaimAsString("customerId");
        if (claim == null) {
            throw new IllegalStateException("JWT is missing the customerId claim");
        }

        boolean admin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_ROLE::equals);

        return new CustomerContext(UUID.fromString(claim), admin);
    }
}
