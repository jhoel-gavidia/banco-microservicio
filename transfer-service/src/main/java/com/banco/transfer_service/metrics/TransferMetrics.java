package com.banco.transfer_service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class TransferMetrics {

    private final Counter created;
    private final Counter completed;
    private final Counter compensated;
    private final Counter failed;
    private final MeterRegistry registry;

    public TransferMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.created = Counter.builder("transfers.created")
                .description("Transferencias creadas")
                .register(registry);
        this.completed = Counter.builder("transfers.saga.completed")
                .description("Sagas finalizadas con exito")
                .register(registry);
        this.compensated = Counter.builder("transfers.saga.compensated")
                .description("Sagas compensadas")
                .register(registry);
        this.failed = Counter.builder("transfers.saga.failed")
                .description("Sagas fallidas")
                .register(registry);
    }

    public void created() {
        created.increment();
    }

    public void completed(Instant startedAt) {
        completed.increment();
        recordDuration("completed", startedAt);
    }

    public void compensated(Instant startedAt) {
        compensated.increment();
        recordDuration("compensated", startedAt);
    }

    public void failed(Instant startedAt) {
        failed.increment();
        recordDuration("failed", startedAt);
    }

    public void clientRetry(String kind) {
        registry.counter("transfers.client.retries", "kind", kind).increment();
    }

    private void recordDuration(String outcome, Instant startedAt) {
        if (startedAt == null) {
            return;
        }
        Duration duration = Duration.between(startedAt, Instant.now());
        if (!duration.isNegative()) {
            registry.timer("transfers.saga.duration", "outcome", outcome).record(duration);
        }
    }
}
