package com.banco.account_service.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

    private static final int MAX_LENGTH = 128;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String correlationId = request.getHeader(CorrelationContext.HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        } else {
            correlationId = correlationId.trim();
            if (correlationId.length() > MAX_LENGTH) {
                correlationId = correlationId.substring(0, MAX_LENGTH);
            }
        }

        CorrelationContext.set(correlationId);
        response.setHeader(CorrelationContext.HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            CorrelationContext.clear();
        }
    }
}
