package com.banco.account_service.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

@Component
public class AuthenticatedCustomer {

    private static final String ADMIN_ROLE = "ROLE_ADMIN";
    private static final String INTERNAL_ROLE = "ROLE_ACCOUNT_INTERNAL";
    private static final String CUSTOMER_ID_HEADER = "X-Customer-Id";

    public CustomerContext current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("No authenticated JWT in the security context");
        }

        boolean internal = hasAuthority(authentication, INTERNAL_ROLE);
        boolean admin = hasAuthority(authentication, ADMIN_ROLE);

        UUID customerId = internal ? customerIdFromHeader() : customerIdFromJwt(jwt);

        return new CustomerContext(customerId, admin || internal);
    }

    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    private UUID customerIdFromJwt(Jwt jwt) {
        String claim = jwt.getClaimAsString("customerId");
        if (claim == null) {
            throw new IllegalStateException("JWT is missing the customerId claim");
        }
        return UUID.fromString(claim);
    }

    private UUID customerIdFromHeader() {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder
                .currentRequestAttributes()).getRequest();
        String value = request.getHeader(CUSTOMER_ID_HEADER);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    CUSTOMER_ID_HEADER + " header is required for internal calls"
            );
        }

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    CUSTOMER_ID_HEADER + " header must be a valid UUID", ex
            );
        }
    }
}
