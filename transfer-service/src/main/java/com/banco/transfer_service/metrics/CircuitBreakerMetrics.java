package com.banco.transfer_service.metrics;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CircuitBreakerMetrics {

    private static final String NAME = "accountService";

    public CircuitBreakerMetrics(
            CircuitBreakerRegistry circuitBreakerRegistry,
            MeterRegistry meterRegistry
    ) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(NAME);

        for (CircuitBreaker.State state : List.of(
                CircuitBreaker.State.CLOSED,
                CircuitBreaker.State.OPEN,
                CircuitBreaker.State.HALF_OPEN
        )) {
            Gauge.builder(
                    "transfers.circuit.state",
                    circuitBreaker,
                    breaker -> breaker.getState() == state ? 1 : 0
            )
                    .tag("state", state.name())
                    .description("Estado actual del circuito (1 para el estado activo)")
                    .register(meterRegistry);
        }

        circuitBreaker.getEventPublisher().onStateTransition(event -> meterRegistry
                .counter(
                        "transfers.circuit.transitions",
                        "from", event.getStateTransition().getFromState().name(),
                        "to", event.getStateTransition().getToState().name()
                )
                .increment());
    }
}
