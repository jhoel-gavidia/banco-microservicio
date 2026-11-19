package com.banco.transfer_service.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component("outbox")
public class OutboxHealthIndicator implements HealthIndicator {

    private final OutboxEventRepository repository;
    private final Duration staleThreshold;

    public OutboxHealthIndicator(
            OutboxEventRepository repository,
            @Value("${outbox.health.stale-threshold}") Duration staleThreshold
    ) {
        this.repository = repository;
        this.staleThreshold = staleThreshold;
    }

    @Override
    public Health health() {
        long pending = repository.countPending();
        Instant oldest = repository.findOldestPendingCreatedAt();
        if (oldest == null) {
            return Health.up()
                    .withDetail("pending", pending)
                    .build();
        }

        Duration age = Duration.between(oldest, Instant.now());
        Health.Builder builder = age.compareTo(staleThreshold) > 0
                ? Health.down()
                : Health.up();
        return builder
                .withDetail("pending", pending)
                .withDetail("oldestPendingAgeSeconds", Math.max(0, age.getSeconds()))
                .withDetail("staleThresholdSeconds", staleThreshold.getSeconds())
                .build();
    }
}
