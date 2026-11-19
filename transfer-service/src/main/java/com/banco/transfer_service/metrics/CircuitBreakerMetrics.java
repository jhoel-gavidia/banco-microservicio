package com.banco.transfer_service.metrics;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class CircuitBreakerMetrics {

    private static final String NAME = "accountService";

    public CircuitBreakerMetrics(
            CircuitBreakerRegistry circuitBreakerRegistry,
            MeterRegistry meterRegistry
    ) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(NAME);

        Gauge.builder("transfers.circuit.state", circuitBreaker, breaker -> breaker.getState().ordinal())
                .description("Estado del circuito: 0=CLOSED, 1=OPEN, 2=HALF_OPEN")
                .register(meterRegistry);

        circuitBreaker.getEventPublisher().onStateTransition(event -> meterRegistry
                .counter(
                        "transfers.circuit.transitions",
                        "from", event.getStateTransition().getFromState().name(),
                        "to", event.getStateTransition().getToState().name()
                )
                .increment());
    }
}
