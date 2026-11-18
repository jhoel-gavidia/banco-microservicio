package com.banco.transfer_service.transfer.service;

import com.banco.transfer_service.client.AccountServiceClient;
import com.banco.transfer_service.client.AccountServiceException;
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

    public TransferOrchestrator(
            TransferRepository repository,
            AccountServiceClient accountServiceClient,
            OutboxService outboxService,
            PlatformTransactionManager transactionManager,
            @Value("${transfer.orchestrator.batch-size}") int batchSize
    ) {
        this.repository = repository;
        this.accountServiceClient = accountServiceClient;
        this.outboxService = outboxService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${transfer.orchestrator.poll-interval:500ms}")
    public void processPending() {
        if (!lock.tryLock()) {
            return;
        }
        try {
            List<UUID> ids = repository.findProcessableIds(
                    PROCESSABLE, PageRequest.of(0, batchSize)
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

        try {
            switch (data.status()) {
                case PENDING -> debit(data);
                case DEBITED, COMPLETING -> credit(data);
                case COMPENSATING -> compensate(data);
                default -> {
                }
            }
        } catch (AccountServiceException ex) {
            handleAccountFailure(data, ex);
        } catch (Exception ex) {
            log.error("Unexpected error processing transfer {}", id, ex);
        }
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
                    Instant.now()
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
                    Instant.now()
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
                    Instant.now()
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
            TransferStatus status
    ) {
        static TransferData from(Transfer transfer) {
            return new TransferData(
                    transfer.getId(),
                    transfer.getCustomerId(),
                    transfer.getSourceAccountId(),
                    transfer.getTargetAccountId(),
                    transfer.getAmount(),
                    transfer.getReference(),
                    transfer.getStatus()
            );
        }
    }
}
