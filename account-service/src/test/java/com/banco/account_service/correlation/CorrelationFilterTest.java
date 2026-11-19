package com.banco.account_service.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationFilterTest {

    private final CorrelationFilter filter = new CorrelationFilter();

    @AfterEach
    void tearDown() {
        CorrelationContext.clear();
    }

    @Test
    void doFilter_whenHeaderIsMissing_generatesIdAndExposesIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> insideChain = new AtomicReference<>();

        filter.doFilter(request, response, capture(insideChain));

        String correlationId = response.getHeader(CorrelationContext.HEADER);
        assertThat(correlationId).isNotBlank();
        assertThat(insideChain.get()).isEqualTo(correlationId);
        assertThat(CorrelationContext.current()).isNull();
    }

    @Test
    void doFilter_whenHeaderIsPresent_trimsAndPreservesIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationContext.HEADER, "  corr-123  ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> insideChain = new AtomicReference<>();

        filter.doFilter(request, response, capture(insideChain));

        assertThat(response.getHeader(CorrelationContext.HEADER)).isEqualTo("corr-123");
        assertThat(insideChain.get()).isEqualTo("corr-123");
    }

    @Test
    void doFilter_whenHeaderIsTooLong_truncatesIt() throws Exception {
        String longId = "x".repeat(200);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationContext.HEADER, longId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, capture(new AtomicReference<>()));

        assertThat(response.getHeader(CorrelationContext.HEADER)).hasSize(128);
    }

    @Test
    void doFilter_whenChainFails_clearsContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failingChain = (req, res) -> {
            assertThat(CorrelationContext.current()).isNotBlank();
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failingChain))
                .isInstanceOf(ServletException.class);

        assertThat(CorrelationContext.current()).isNull();
    }

    private FilterChain capture(AtomicReference<String> holder) {
        return (request, response) -> {
            holder.set(CorrelationContext.current());
        };
    }
}
