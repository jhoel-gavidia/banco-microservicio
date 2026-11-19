package com.banco.account_service.outbox.event;

import com.banco.account_service.ledger.LedgerEntryType;
import com.banco.account_service.outbox.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountMovementEvent(
        UUID eventId,
        UUID accountId,
        UUID customerId,
        LedgerEntryType type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String reference,
        Instant occurredAt,
        String eventType,
        String correlationId
) implements DomainEvent {

    @Override
    public UUID aggregateId() {
        return accountId;
    }
}
