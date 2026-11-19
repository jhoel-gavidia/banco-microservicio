package com.banco.transfer_service.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
    void doFilter_whenHeaderIsPresent_trimsIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationContext.HEADER, "  corr-transfer  ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> insideChain = new AtomicReference<>();

        filter.doFilter(request, response, capture(insideChain));

        assertThat(response.getHeader(CorrelationContext.HEADER)).isEqualTo("corr-transfer");
        assertThat(insideChain.get()).isEqualTo("corr-transfer");
    }

    @Test
    void doFilter_whenChainFails_clearsContext() {
        FilterChain failingChain = (request, response) -> {
            assertThat(CorrelationContext.current()).isNotBlank();
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(), failingChain
        )).isInstanceOf(ServletException.class);

        assertThat(CorrelationContext.current()).isNull();
    }

    private FilterChain capture(AtomicReference<String> holder) {
        return (request, response) -> holder.set(CorrelationContext.current());
    }
}
