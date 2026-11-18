package com.banco.transfer_service.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    private UUID id;

    @org.hibernate.annotations.Generated(
            event = org.hibernate.generator.EventType.INSERT
    )
    @Column(name = "sequence_no", nullable = false, updatable = false, insertable = false)
    private Long sequenceNo;

    @Column(name = "aggregate_type", nullable = false, length = 30)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @org.hibernate.annotations.Generated(
            event = org.hibernate.generator.EventType.INSERT
    )
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    private OutboxEvent(
            UUID eventId,
            UUID aggregateId,
            String aggregateType,
            String eventType,
            String payload,
            Instant occurredAt
    ) {
        this.id = eventId;
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
        this.eventType = eventType;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.nextAttemptAt = occurredAt;
    }

    public static OutboxEvent record(
            UUID eventId,
            UUID aggregateId,
            String aggregateType,
            String eventType,
            String payload,
            Instant occurredAt
    ) {
        return new OutboxEvent(
                eventId, aggregateId, aggregateType, eventType, payload, occurredAt
        );
    }

    public void markPublished(Instant at) {
        this.publishedAt = at;
    }

    public void markFailed(Instant nextAttempt) {
        this.attempts++;
        this.nextAttemptAt = nextAttempt;
    }
}
