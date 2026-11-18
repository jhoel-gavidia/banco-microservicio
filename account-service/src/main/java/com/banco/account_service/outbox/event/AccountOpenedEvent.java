package com.banco.account_service.outbox.event;

import com.banco.account_service.outbox.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record AccountOpenedEvent(
        UUID eventId,
        UUID accountId,
        UUID customerId,
        String number,
        String currency,
        Instant occurredAt,
        String eventType
) implements DomainEvent {

    @Override
    public UUID aggregateId() {
        return accountId;
    }


}