package com.banco.transfer_service.transfer.service;

import com.banco.transfer_service.client.AccountServiceClient;
import com.banco.transfer_service.client.AccountServiceException;
import com.banco.transfer_service.client.AccountServiceUnavailableException;
import com.banco.transfer_service.correlation.CorrelationContext;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import com.banco.transfer_service.outbox.OutboxService;
import com.banco.transfer_service.outbox.event.TransferCompensatedEvent;
import com.banco.transfer_service.outbox.event.TransferCompletedEvent;
import com.banco.transfer_service.outbox.event.TransferDebitedEvent;
import com.banco.transfer_service.transfer.domain.Money;
import com.banco.transfer_service.transfer.domain.Transfer;
import com.banco.transfer_service.transfer.domain.TransferStatus;
import com.banco.transfer_service.transfer.repository.TransferRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
@ConditionalOnProperty(
        name = "transfer.orchestrator.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class TransferOrchestrator {

    private static final List<TransferStatus> PROCESSABLE = List.of(
            TransferStatus.PENDING,
            TransferStatus.DEBITED,
            TransferStatus.COMPLETING,
            TransferStatus.COMPENSATING
    );

    private final TransferRepository repository;
    private final AccountServiceClient accountServiceClient;
    private final OutboxService outboxService;
    private final TransactionTemplate transactionTemplate;
    private final ReentrantLock lock = new ReentrantLock();
    private final int batchSize;
    private final Duration retryBackoff;
    private final Duration circuitOpenBackoff;

    public TransferOrchestrator(
            TransferRepository repository,
            AccountServiceClient accountServiceClient,
            OutboxService outboxService,
            PlatformTransactionManager transactionManager,
            @Value("${transfer.orchestrator.batch-size}") int batchSize,
            @Value("${transfer.orchestrator.retry-backoff}") Duration retryBackoff,
            @Value("${transfer.orchestrator.circuit-open-backoff}") Duration circuitOpenBackoff
    ) {
        this.repository = repository;
        this.accountServiceClient = accountServiceClient;
        this.outboxService = outboxService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.retryBackoff = retryBackoff;
        this.circuitOpenBackoff = circuitOpenBackoff;
    }

    @Scheduled(fixedDelayString = "${transfer.orchestrator.poll-interval:500ms}")
    public void processPending() {
        if (!lock.tryLock()) {
            return;
        }
        try {
            List<UUID> ids = repository.findProcessableIds(
                    PROCESSABLE, Instant.now(), PageRequest.of(0, batchSize)
            );
            for (UUID id : ids) {
                process(id);
            }
        } catch (Exception ex) {
            log.error("Could not run transfer orchestrator", ex);
        } finally {
            lock.unlock();
        }
    }

    private void process(UUID id) {
        TransferData data = repository.findById(id).map(TransferData::from).orElse(null);
        if (data == null) {
            return;
        }

        if (data.correlationId() != null && !data.correlationId().isBlank()) {
            CorrelationContext.set(data.correlationId());
        }

        try {
            try {
                switch (data.status()) {
                    case PENDING -> debit(data);
                    case DEBITED, COMPLETING -> credit(data);
                    case COMPENSATING -> compensate(data);
                    default -> {
                    }
                }
            } catch (CallNotPermittedException ex) {
                handleTransientFailure(data, circuitOpenBackoff, "account-service circuit is open");
            } catch (BulkheadFullException ex) {
                handleTransientFailure(data, retryBackoff, "account-service bulkhead is full");
            } catch (AccountServiceUnavailableException ex) {
                handleTransientFailure(data, retryBackoff, ex.getMessage());
            } catch (AccountServiceException ex) {
                handleAccountFailure(data, ex);
            } catch (Exception ex) {
                log.error("Unexpected error processing transfer {}", id, ex);
            }
        } finally {
            CorrelationContext.clear();
        }
    }

    private void handleTransientFailure(TransferData data, Duration delay, String reason) {
        if (data.status() != TransferStatus.PENDING
                && data.status() != TransferStatus.DEBITED
                && data.status() != TransferStatus.COMPLETING
                && data.status() != TransferStatus.COMPENSATING) {
            return;
        }

        Instant nextAttempt = Instant.now().plus(delay);
        log.warn(
                "Transient failure processing transfer {}: {}, retrying at {}",
                data.id(), reason, nextAttempt
        );
        applyRetry(data.id(), nextAttempt, reason);
    }

    private void applyRetry(UUID id, Instant nextAttempt, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            Transfer transfer = repository.findById(id).orElseThrow();
            transfer.scheduleRetry(nextAttempt, reason);
            repository.save(transfer);
        });
    }

    private void debit(TransferData data) {
        accountServiceClient.withdraw(
                data.sourceAccountId(),
                data.customerId(),
                data.amount().getAmount(),
                data.reference(),
                idempotencyKey(data.id(), "debit")
        );
        applyDebited(data);
    }

    private void credit(TransferData data) {
        accountServiceClient.deposit(
                data.targetAccountId(),
                data.customerId(),
                data.amount().getAmount(),
                data.reference(),
                idempotencyKey(data.id(), "credit")
        );
        applyCompleted(data);
    }

    private void compensate(TransferData data) {
        accountServiceClient.deposit(
                data.sourceAccountId(),
                data.customerId(),
                data.amount().getAmount(),
                refundReference(data.reference()),
                idempotencyKey(data.id(), "refund")
        );
        applyCompensated(data.id());
    }

    private void handleAccountFailure(TransferData data, AccountServiceException ex) {
        String reason = ex.getMessage();
        switch (data.status()) {
            case PENDING -> applyFailed(data.id(), reason);
            case DEBITED, COMPLETING -> applyCompensating(data.id(), reason);
            case COMPENSATING -> log.warn(
                    "Compensation still failing for transfer {}: {}", data.id(), reason
            );
            default -> {
            }
        }
    }

    private void applyDebited(TransferData data) {
        transactionTemplate.executeWithoutResult(status -> {
            Transfer transfer = repository.findById(data.id()).orElseThrow();
            transfer.markDebited();
            outboxService.record(new TransferDebitedEvent(
                    UUID.randomUUID(),
                    transfer.getId(),
                    transfer.getCustomerId(),
                    transfer.getSourceAccountId(),
                    transfer.getAmount().getAmount(),
                    transfer.getAmount().getCurrency(),
                    Instant.now(),
                    transfer.getCorrelationId()
            ));
            repository.save(transfer);
        });
    }

    private void applyCompleted(TransferData data) {
        transactionTemplate.executeWithoutResult(status -> {
            Transfer transfer = repository.findById(data.id()).orElseThrow();
            if (transfer.getStatus() == TransferStatus.DEBITED) {
                transfer.markCompleting();
            }
            transfer.markCompleted();
            outboxService.record(new TransferCompletedEvent(
                    UUID.randomUUID(),
                    transfer.getId(),
                    transfer.getCustomerId(),
                    transfer.getSourceAccountId(),
                    transfer.getTargetAccountId(),
                    transfer.getAmount().getAmount(),
                    transfer.getAmount().getCurrency(),
                    Instant.now(),
                    transfer.getCorrelationId()
            ));
            repository.save(transfer);
        });
    }

    private void applyCompensating(UUID id, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            Transfer transfer = repository.findById(id).orElseThrow();
            transfer.markCompensating(reason);
            repository.save(transfer);
        });
    }

    private void applyCompensated(UUID id) {
        transactionTemplate.executeWithoutResult(status -> {
            Transfer transfer = repository.findById(id).orElseThrow();
            transfer.markCompensated();
            outboxService.record(new TransferCompensatedEvent(
                    UUID.randomUUID(),
                    transfer.getId(),
                    transfer.getCustomerId(),
                    transfer.getSourceAccountId(),
                    transfer.getAmount().getAmount(),
                    transfer.getAmount().getCurrency(),
                    transfer.getFailureReason(),
                    Instant.now(),
                    transfer.getCorrelationId()
            ));
            repository.save(transfer);
        });
    }

    private void applyFailed(UUID id, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            Transfer transfer = repository.findById(id).orElseThrow();
            transfer.markFailed(reason);
            repository.save(transfer);
        });
    }

    private static String idempotencyKey(UUID transferId, String operation) {
        return "transfer-" + transferId + "-" + operation;
    }

    private static String refundReference(String reference) {
        String refund = "REFUND-" + reference;
        return refund.length() <= 100 ? refund : refund.substring(0, 100);
    }

    private record TransferData(
            UUID id,
            UUID customerId,
            UUID sourceAccountId,
            UUID targetAccountId,
            Money amount,
            String reference,
            TransferStatus status,
            String correlationId
    ) {
        static TransferData from(Transfer transfer) {
            return new TransferData(
                    transfer.getId(),
                    transfer.getCustomerId(),
                    transfer.getSourceAccountId(),
                    transfer.getTargetAccountId(),
                    transfer.getAmount(),
                    transfer.getReference(),
                    transfer.getStatus(),
                    transfer.getCorrelationId()
            );
        }
    }
}
