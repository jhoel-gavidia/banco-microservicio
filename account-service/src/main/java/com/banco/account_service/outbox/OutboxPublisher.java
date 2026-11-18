package com.banco.account_service.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "outbox.publisher.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${outbox.topic}")
    private String topic;

    @Value("${outbox.batch-size}")
    private int batchSize;

    @Value("${outbox.max-attempts}")
    private int maxAttempts;

    @Value("${outbox.retry-backoff}")
    private Duration retryBackoff;

    @Scheduled(fixedDelayString = "${outbox.poll-interval:500ms}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> events = repository.findPending(Instant.now(), batchSize);
        for (OutboxEvent event : events) {
            publish(event);
        }
    }

    private void publish(OutboxEvent event) {
        try {
            send(topic, event);
            event.markPublished(Instant.now());
        } catch (Exception ex) {
            handleFailure(event, ex);
        }
    }

    private void send(String targetTopic, OutboxEvent event) throws Exception {
        CompletableFuture<SendResult<String, String>> future =
                kafkaTemplate.send(targetTopic, event.getAggregateId().toString(), event.getPayload());
        future.get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void handleFailure(OutboxEvent event, Exception ex) {
        int attempted = event.getAttempts() + 1;
        Instant now = Instant.now();
        if (attempted >= maxAttempts) {
            try {
                send(topic + ".dlq", event);
                event.markPublished(now);
                log.warn("Evento {} enviado a la cola de fallos tras {} intentos", event.getId(), attempted, ex);
            } catch (Exception dlqError) {
                event.markFailed(now.plus(retryBackoff.multipliedBy(attempted)));
                log.error("No se pudo enviar el evento {} a la cola de fallos", event.getId(), dlqError);
            }
            return;
        }
        event.markFailed(now.plus(retryBackoff.multipliedBy(attempted)));
        log.warn("Publicacion de {} fallida (intento {}). Reintentando en {}",
                event.getId(), attempted, retryBackoff.multipliedBy(attempted), ex);
    }
}