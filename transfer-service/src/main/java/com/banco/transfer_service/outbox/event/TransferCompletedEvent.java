package com.banco.transfer_service.outbox.event;

import com.banco.transfer_service.outbox.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferCompletedEvent(
        UUID eventId,
        UUID transferId,
        UUID customerId,
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        String currency,
        Instant occurredAt,
        String correlationId
) implements DomainEvent {

    @Override
    public UUID aggregateId() {
        return transferId;
    }

    @Override
    public String eventType() {
        return "TransferCompleted";
    }
}
