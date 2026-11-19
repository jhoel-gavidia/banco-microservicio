package com.banco.transfer_service.outbox;

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
            new OutboxHealthIndicator(repository, Duration.ofMinutes(2));

    @Test
    void health_withoutPendingEvents_returnsUp() {
        when(repository.countPending()).thenReturn(0L);
        when(repository.findOldestPendingCreatedAt()).thenReturn(null);

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("pending", 0L);
    }

    @Test
    void health_withRecentPendingEvent_returnsUp() {
        when(repository.countPending()).thenReturn(2L);
        when(repository.findOldestPendingCreatedAt()).thenReturn(Instant.now().minusSeconds(30));

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("pending", 2L);
    }

    @Test
    void health_withStalePendingEvent_returnsDown() {
        when(repository.countPending()).thenReturn(1L);
        when(repository.findOldestPendingCreatedAt()).thenReturn(Instant.now().minusSeconds(180));

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("staleThresholdSeconds", 120L);
    }
}
