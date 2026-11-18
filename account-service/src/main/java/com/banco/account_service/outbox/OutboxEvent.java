package com.banco.account_service.outbox;

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

    private OutboxEvent(DomainEvent event, String aggregateType, String payload) {
        this.id = event.eventId();
        this.aggregateType = aggregateType;
        this.aggregateId = event.aggregateId();
        this.eventType = event.eventType();
        this.payload = payload;
        this.occurredAt = event.occurredAt();
        this.nextAttemptAt = event.occurredAt();
    }

    public static OutboxEvent from(DomainEvent event, String aggregateType, String payload) {
        return new OutboxEvent(event, aggregateType, payload);
    }

    public void markPublished(Instant at) {
        this.publishedAt = at;
    }

    public void markFailed(Instant nextAttempt) {
        this.attempts++;
        this.nextAttemptAt = nextAttempt;
    }
}