package com.banco.transfer_service.outbox.event;

import com.banco.transfer_service.outbox.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferCompensatedEvent(
        UUID eventId,
        UUID transferId,
        UUID customerId,
        UUID sourceAccountId,
        BigDecimal amount,
        String currency,
        String reason,
        Instant occurredAt,
        String correlationId
) implements DomainEvent {

    @Override
    public UUID aggregateId() {
        return transferId;
    }

    @Override
    public String eventType() {
        return "TransferCompensated";
    }
}
