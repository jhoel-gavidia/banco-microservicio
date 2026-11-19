package com.banco.account_service.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxHealthIndicatorTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final OutboxHealthIndicator indicator =
            new OutboxHealthIndicator(repository, Duration.ofMinutes(1));

    @Test
    void health_withoutPendingEvents_returnsUp() {
        when(repository.countPending()).thenReturn(0L);
        when(repository.findOldestPendingCreatedAt()).thenReturn(null);

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("pending", 0L);
    }

    @Test
    void health_withRecentPendingEvent_returnsUpWithAge() {
        when(repository.countPending()).thenReturn(3L);
        when(repository.findOldestPendingCreatedAt()).thenReturn(Instant.now().minusSeconds(10));

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("pending", 3L);
        assertThat(health.getDetails()).containsKey("oldestPendingAgeSeconds");
    }

    @Test
    void health_withStalePendingEvent_returnsDown() {
        when(repository.countPending()).thenReturn(1L);
        when(repository.findOldestPendingCreatedAt()).thenReturn(Instant.now().minusSeconds(120));

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("pending", 1L);
        assertThat(health.getDetails()).containsEntry("staleThresholdSeconds", 60L);
    }
}
