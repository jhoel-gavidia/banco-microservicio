package com.banco.transfer_service.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OutboxMetrics {

    private final OutboxEventRepository repository;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();
    private final Counter published;
    private final Counter deadLettered;
    private final Timer publishLag;

    public OutboxMetrics(OutboxEventRepository repository, MeterRegistry registry) {
        this.repository = repository;
        this.published = Counter.builder("outbox.events.published")
                .description("Eventos publicados en el topic principal")
                .register(registry);
        this.deadLettered = Counter.builder("outbox.events.dlq")
                .description("Eventos enviados a la cola de fallos")
                .register(registry);
        this.publishLag = Timer.builder("outbox.publish.lag")
                .description("Tiempo entre el evento y su publicacion")
                .register(registry);

        Gauge.builder("outbox.events.pending", pending, value -> value.get())
                .description("Eventos pendientes de publicacion")
                .register(registry);
        Gauge.builder("outbox.events.oldest.age.seconds", oldestAgeSeconds, value -> value.get())
                .description("Edad del evento pendiente mas antiguo")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${outbox.metrics.refresh-interval:5s}")
    public void refresh() {
        pending.set(repository.countPending());
        Instant oldest = repository.findOldestPendingCreatedAt();
        if (oldest == null) {
            oldestAgeSeconds.set(0);
            return;
        }
        Duration age = Duration.between(oldest, Instant.now());
        oldestAgeSeconds.set(Math.max(0, age.getSeconds()));
    }

    public void published(Duration lag) {
        published.increment();
        if (lag != null && !lag.isNegative()) {
            publishLag.record(lag);
        }
    }

    public void deadLettered() {
        deadLettered.increment();
    }
}
