package com.banco.transfer_service.outbox;

import java.time.Instant;
import java.util.UUID;

public interface DomainEvent {

    UUID eventId();

    UUID aggregateId();

    String eventType();

    Instant occurredAt();

    String correlationId();
}
