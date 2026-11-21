package com.banco.api_gateway.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

    private static final int MAX_LENGTH = 128;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(CorrelationContext.HEADER);
        String correlationId = StringUtils.hasText(incoming)
                ? incoming.trim()
                : UUID.randomUUID().toString();
        if (correlationId.length() > MAX_LENGTH) {
            correlationId = correlationId.substring(0, MAX_LENGTH);
        }

        CorrelationContext.set(correlationId);
        response.setHeader(CorrelationContext.HEADER, correlationId);

        HttpServletRequest wrapped = new CorrelationRequestWrapper(request, correlationId);
        try {
            filterChain.doFilter(wrapped, response);
        } finally {
            CorrelationContext.clear();
        }
    }

    private static final class CorrelationRequestWrapper extends HttpServletRequestWrapper {

        private final String correlationId;

        private CorrelationRequestWrapper(HttpServletRequest request, String correlationId) {
            super(request);
            this.correlationId = correlationId;
        }

        @Override
        public String getHeader(String name) {
            if (CorrelationContext.HEADER.equalsIgnoreCase(name)) {
                return correlationId;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (CorrelationContext.HEADER.equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(correlationId));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            boolean present = names.stream()
                    .anyMatch(name -> CorrelationContext.HEADER.equalsIgnoreCase(name));
            if (!present) {
                names.add(CorrelationContext.HEADER);
            }
            return Collections.enumeration(names);
        }
    }
}