package com.banco.api_gateway.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/accounts", "/api/v1/accounts/**")
                        .access(requireAny(
                                "ROLE_ADMIN", "ROLE_ACCOUNT_READ", "ROLE_ACCOUNT_INTERNAL"
                        ))
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts/*/deposits")
                        .access(requireAny("ROLE_ADMIN", "ROLE_ACCOUNT_INTERNAL"))
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts", "/api/v1/accounts/**")
                        .access(requireAny(
                                "ROLE_ADMIN", "ROLE_ACCOUNT_WRITE", "ROLE_ACCOUNT_INTERNAL"
                        ))
                        .requestMatchers(HttpMethod.GET, "/api/v1/transfers", "/api/v1/transfers/**")
                        .access(requireAll("SCOPE_transfer.read", "ROLE_TRANSFER_READ"))
                        .requestMatchers(HttpMethod.POST, "/api/v1/transfers/*/receipts")
                        .access(requireAll("SCOPE_transfer.write", "ROLE_TRANSFER_WRITE"))
                        .requestMatchers(HttpMethod.POST, "/api/v1/transfers")
                        .access(requireAll("SCOPE_transfer.write", "ROLE_TRANSFER_WRITE"))
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemEntryPoint())
                        .accessDeniedHandler(problemAccessDeniedHandler())
                )
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(problemEntryPoint())
                        .accessDeniedHandler(problemAccessDeniedHandler())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                );

        return http.build();
    }

    private AuthorizationManager<RequestAuthorizationContext> requireAll(String... expected) {
        List<String> required = List.of(expected);
        return (authentication, context) ->
                new AuthorizationDecision(authorities(authentication).containsAll(required));
    }

    private AuthorizationManager<RequestAuthorizationContext> requireAny(String... allowed) {
        List<String> permitted = List.of(allowed);
        return (authentication, context) -> new AuthorizationDecision(
                authorities(authentication).stream().anyMatch(permitted::contains)
        );
    }

    private List<String> authorities(Supplier<? extends Authentication> authentication) {
        Authentication auth = authentication == null ? null : authentication.get();
        if (auth == null) {
            return List.of();
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    private AuthenticationEntryPoint problemEntryPoint() {
        return (request, response, exception) -> writeProblem(
                response,
                SecurityProblems.unauthorized("A valid Bearer token is required"),
                request.getRequestURI()
        );
    }

    private AccessDeniedHandler problemAccessDeniedHandler() {
        return (request, response, exception) -> writeProblem(
                response,
                SecurityProblems.forbidden("Insufficient scope or role for this operation"),
                request.getRequestURI()
        );
    }

    private void writeProblem(
            HttpServletResponse response,
            ProblemDetail problem,
            String instance
    ) throws IOException {
        problem.setInstance(URI.create(instance));
        response.setStatus(problem.getStatus());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));

            Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles) {
                roles.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .map(role -> new SimpleGrantedAuthority(
                                "ROLE_" + role.toUpperCase(Locale.ROOT)
                        ))
                        .forEach(authorities::add);
            }

            return authorities;
        });

        return converter;
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri
    ) {
        String jwkSetUri = issuerUri + "/protocol/openid-connect/certs";
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri));
        return decoder;
    }
}