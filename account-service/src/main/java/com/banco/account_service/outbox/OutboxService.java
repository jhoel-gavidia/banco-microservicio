package com.banco.account_service.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public void record(String aggregateType, DomainEvent event) {
        repository.save(OutboxEvent.from(event, aggregateType, serialize(event)));
    }

    private String serialize(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Could not serialize outbox event", ex);
        }
    }
}