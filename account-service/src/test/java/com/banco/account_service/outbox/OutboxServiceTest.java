package com.banco.account_service.outbox;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxServiceTest {

    @Test
    void record_serializesEventAndStoresItWithAggregateMetadata() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        OutboxService service = new OutboxService(repository, objectMapper);

        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        DomainEvent event = new DomainEvent() {
            @Override
            public UUID eventId() {
                return eventId;
            }

            @Override
            public UUID aggregateId() {
                return aggregateId;
            }

            @Override
            public String eventType() {
                return "ACCOUNT_OPENED";
            }

            @Override
            public Instant occurredAt() {
                return occurredAt;
            }

            @Override
            public String correlationId() {
                return "corr-123";
            }
        };
        String payload = "{\"correlationId\":\"corr-123\"}";
        when(objectMapper.writeValueAsString(event)).thenReturn(payload);

        service.record("ACCOUNT", event);

        ArgumentCaptor<OutboxEvent> stored = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(stored.capture());
        assertThat(stored.getValue().getId()).isEqualTo(eventId);
        assertThat(stored.getValue().getAggregateType()).isEqualTo("ACCOUNT");
        assertThat(stored.getValue().getAggregateId()).isEqualTo(aggregateId);
        assertThat(stored.getValue().getEventType()).isEqualTo("ACCOUNT_OPENED");
        assertThat(stored.getValue().getPayload()).isEqualTo(payload);
        assertThat(stored.getValue().getOccurredAt()).isEqualTo(occurredAt);
    }
}
