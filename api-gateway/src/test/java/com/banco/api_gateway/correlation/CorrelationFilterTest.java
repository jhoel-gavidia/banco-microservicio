package com.banco.api_gateway.correlation;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationFilterTest {

    private static final String HEADER = CorrelationContext.HEADER;

    private final CorrelationFilter filter = new CorrelationFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void generatesHeaderWhenMissingAndClearsMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> downstream = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> downstream.set((HttpServletRequest) req));

        String correlationId = response.getHeader(HEADER);
        assertThat(correlationId).isNotBlank();
        assertThat(downstream.get().getHeader(HEADER)).isEqualTo(correlationId);
        assertThat(MDC.get(CorrelationContext.MDC_KEY)).isNull();
    }

    @Test
    void preservesAndTrimsIncomingHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, "  corr-from-postman  ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> downstream = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> downstream.set((HttpServletRequest) req));

        assertThat(response.getHeader(HEADER)).isEqualTo("corr-from-postman");
        assertThat(downstream.get().getHeader(HEADER)).isEqualTo("corr-from-postman");
    }

    @Test
    void truncatesHeaderLongerThanLimit() throws Exception {
        String longId = "x".repeat(200);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, longId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getHeader(HEADER)).hasSize(128);
    }
}