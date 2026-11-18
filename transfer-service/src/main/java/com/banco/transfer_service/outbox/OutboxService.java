package com.banco.transfer_service.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class OutboxService {

    private static final String AGGREGATE_TYPE = "transfer";

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public void record(DomainEvent event) {
        repository.save(OutboxEvent.record(
                event.eventId(),
                event.aggregateId(),
                AGGREGATE_TYPE,
                event.eventType(),
                serialize(event),
                event.occurredAt()
        ));
    }

    private String serialize(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Could not serialize outbox event", ex);
        }
    }
}
