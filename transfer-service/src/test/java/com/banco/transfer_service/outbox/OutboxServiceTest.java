package com.banco.transfer_service.outbox;

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
    void record_serializesEventWithTransferAggregateMetadata() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        OutboxService service = new OutboxService(repository, objectMapper);

        UUID eventId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        DomainEvent event = new DomainEvent() {
            @Override
            public UUID eventId() {
                return eventId;
            }

            @Override
            public UUID aggregateId() {
                return transferId;
            }

            @Override
            public String eventType() {
                return "TransferInitiated";
            }

            @Override
            public Instant occurredAt() {
                return occurredAt;
            }

            @Override
            public String correlationId() {
                return "corr-transfer";
            }
        };
        String payload = "{\"correlationId\":\"corr-transfer\"}";
        when(objectMapper.writeValueAsString(event)).thenReturn(payload);

        service.record(event);

        ArgumentCaptor<OutboxEvent> stored = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(stored.capture());
        assertThat(stored.getValue().getId()).isEqualTo(eventId);
        assertThat(stored.getValue().getAggregateType()).isEqualTo("transfer");
        assertThat(stored.getValue().getAggregateId()).isEqualTo(transferId);
        assertThat(stored.getValue().getEventType()).isEqualTo("TransferInitiated");
        assertThat(stored.getValue().getPayload()).isEqualTo(payload);
        assertThat(stored.getValue().getOccurredAt()).isEqualTo(occurredAt);
    }
}
