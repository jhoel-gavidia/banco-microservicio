package com.banco.account_service.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OutboxPublisherTest {

    private static final String TOPIC = "account.movements.v1";

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final OutboxMetrics metrics = mock(OutboxMetrics.class);

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(repository, kafkaTemplate, metrics);
        ReflectionTestUtils.setField(publisher, "topic", TOPIC);
        ReflectionTestUtils.setField(publisher, "batchSize", 10);
        ReflectionTestUtils.setField(publisher, "maxAttempts", 3);
        ReflectionTestUtils.setField(publisher, "retryBackoff", Duration.ofSeconds(1));
    }

    @Test
    void publishPending_whenSendSucceeds_marksPublishedAndRecordsMetric() {
        OutboxEvent event = newEvent();
        when(repository.findPending(any(Instant.class), anyInt())).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPending();

        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getAttempts()).isZero();
        verify(kafkaTemplate).send(TOPIC, event.getAggregateId().toString(), event.getPayload());
        verify(metrics).published(any(Duration.class));
        verify(metrics, never()).deadLettered();
    }

    @Test
    void publishPending_whenSendFails_schedulesRetryWithBackoff() {
        OutboxEvent event = newEvent();
        when(repository.findPending(any(Instant.class), anyInt())).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("kafka down")));

        publisher.publishPending();

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isAfter(event.getOccurredAt());
        verify(metrics, never()).published(any(Duration.class));
    }

    @Test
    void publishPending_whenMaxAttemptsAreExceeded_sendsToDlq() {
        OutboxEvent event = newEvent();
        when(repository.findPending(any(Instant.class), anyInt())).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            if (invocation.getArgument(0, String.class).endsWith(".dlq")) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.failedFuture(new RuntimeException("kafka down"));
        });

        publisher.publishPending();
        publisher.publishPending();
        publisher.publishPending();

        assertThat(event.getAttempts()).isEqualTo(2);
        assertThat(event.getPublishedAt()).isNotNull();

        ArgumentCaptor<String> topics = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(4)).send(topics.capture(), anyString(), anyString());
        assertThat(topics.getAllValues()).containsExactly(TOPIC, TOPIC, TOPIC, TOPIC + ".dlq");
        verify(metrics).deadLettered();
    }

    @Test
    void publishPending_whenDlqAlsoFails_keepsEventPending() {
        OutboxEvent event = newEvent();
        when(repository.findPending(any(Instant.class), anyInt())).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("kafka down")));

        publisher.publishPending();
        publisher.publishPending();
        publisher.publishPending();

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttempts()).isEqualTo(3);
        verify(metrics, never()).deadLettered();
    }

    private OutboxEvent newEvent() {
        UUID aggregateId = UUID.randomUUID();
        Instant occurredAt = Instant.now().minusSeconds(5);
        DomainEvent event = new DomainEvent() {
            @Override
            public UUID eventId() {
                return UUID.randomUUID();
            }

            @Override
            public UUID aggregateId() {
                return aggregateId;
            }

            @Override
            public String eventType() {
                return "ACCOUNT_MOVEMENT_RECORDED";
            }

            @Override
            public Instant occurredAt() {
                return occurredAt;
            }

            @Override
            public String correlationId() {
                return "corr-outbox";
            }
        };
        return OutboxEvent.from(event, "ACCOUNT", "{\"eventType\":\"ACCOUNT_MOVEMENT_RECORDED\"}");
    }
}
