package com.banco.transfer_service.transfer.service;

import com.banco.transfer_service.client.AccountServiceClient;
import com.banco.transfer_service.client.AccountServiceException;
import com.banco.transfer_service.client.AccountServiceUnavailableException;
import com.banco.transfer_service.correlation.CorrelationContext;
import com.banco.transfer_service.metrics.TransferMetrics;
import com.banco.transfer_service.outbox.DomainEvent;
import com.banco.transfer_service.outbox.OutboxService;
import com.banco.transfer_service.outbox.event.TransferCompensatedEvent;
import com.banco.transfer_service.outbox.event.TransferCompletedEvent;
import com.banco.transfer_service.outbox.event.TransferDebitedEvent;
import com.banco.transfer_service.transfer.domain.Money;
import com.banco.transfer_service.transfer.domain.Transfer;
import com.banco.transfer_service.transfer.domain.TransferStatus;
import com.banco.transfer_service.transfer.repository.TransferRepository;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferOrchestratorTest {

    private static final UUID TRANSFER_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("25.00");
    private static final String CORRELATION = "corr-saga";

    private final TransferRepository repository = mock(TransferRepository.class);
    private final AccountServiceClient client = mock(AccountServiceClient.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransferMetrics metrics = mock(TransferMetrics.class);

    private TransferOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        when(repository.findProcessableIds(anyCollection(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(TRANSFER_ID));
        orchestrator = new TransferOrchestrator(
                repository, client, outboxService, transactionManager, metrics,
                10, Duration.ofSeconds(2), Duration.ofSeconds(30)
        );
    }

    @AfterEach
    void tearDown() {
        CorrelationContext.clear();
    }

    @Test
    void pendingTransfer_debitsAndRecordsDebitedEvent() {
        Transfer transfer = stubTransfer(TransferStatus.PENDING);
        AtomicReference<String> correlationInClient = new AtomicReference<>();
        doAnswer(invocation -> {
            correlationInClient.set(CorrelationContext.current());
            return null;
        }).when(client).withdraw(any(UUID.class), any(UUID.class), any(BigDecimal.class),
                anyString(), anyString());

        orchestrator.processPending();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.DEBITED);
        verify(client).withdraw(
                SOURCE, CUSTOMER_ID, AMOUNT, "TRX-1",
                "transfer-" + TRANSFER_ID + "-debit"
        );
        assertThat(correlationInClient.get()).isEqualTo(CORRELATION);
        assertThat(CorrelationContext.current()).isNull();

        DomainEvent event = capturedEvent();
        assertThat(event).isInstanceOf(TransferDebitedEvent.class);
        assertThat(event.eventType()).isEqualTo("TransferDebited");
        assertThat(event.correlationId()).isEqualTo(CORRELATION);
        verify(repository).save(transfer);
    }

    @Test
    void debitedTransfer_creditsAndRecordsCompletedEvent() {
        Transfer transfer = stubTransfer(TransferStatus.DEBITED);

        orchestrator.processPending();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        verify(client).deposit(
                TARGET, CUSTOMER_ID, AMOUNT, "TRX-1",
                "transfer-" + TRANSFER_ID + "-credit"
        );
        DomainEvent event = capturedEvent();
        assertThat(event).isInstanceOf(TransferCompletedEvent.class);
        assertThat(event.eventType()).isEqualTo("TransferCompleted");
        verify(metrics).completed(transfer.getCreatedAt());
    }

    @Test
    void pendingTransfer_withBusinessFailure_marksFailedWithoutOutbox() {
        Transfer transfer = stubTransfer(TransferStatus.PENDING);
        when(client.withdraw(any(UUID.class), any(UUID.class), any(BigDecimal.class),
                anyString(), anyString()))
                .thenThrow(businessFailure());

        orchestrator.processPending();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.getFailureReason()).contains("422");
        verify(outboxService, never()).record(any(DomainEvent.class));
        verify(metrics).failed(transfer.getCreatedAt());
    }

    @Test
    void debitedTransfer_withBusinessFailure_marksCompensating() {
        Transfer transfer = stubTransfer(TransferStatus.DEBITED);
        when(client.deposit(any(UUID.class), any(UUID.class), any(BigDecimal.class),
                anyString(), anyString()))
                .thenThrow(businessFailure());

        orchestrator.processPending();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATING);
        assertThat(transfer.getFailureReason()).contains("422");
        verify(outboxService, never()).record(any(DomainEvent.class));
    }

    @Test
    void compensatingTransfer_refundsSourceAndRecordsCompensatedEvent() {
        Transfer transfer = stubTransfer(TransferStatus.COMPENSATING);
        ReflectionTestUtils.setField(transfer, "failureReason", "credit failed");

        orchestrator.processPending();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATED);
        verify(client).deposit(
                SOURCE, CUSTOMER_ID, AMOUNT, "REFUND-TRX-1",
                "transfer-" + TRANSFER_ID + "-refund"
        );
        DomainEvent event = capturedEvent();
        assertThat(event).isInstanceOf(TransferCompensatedEvent.class);
        assertThat(event.eventType()).isEqualTo("TransferCompensated");
        verify(metrics).compensated(transfer.getCreatedAt());
    }

    @Test
    void transientFailure_schedulesRetryWithoutChangingStatus() {
        Transfer transfer = stubTransfer(TransferStatus.PENDING);
        when(client.withdraw(any(UUID.class), any(UUID.class), any(BigDecimal.class),
                anyString(), anyString()))
                .thenThrow(new AccountServiceUnavailableException("unreachable"));

        orchestrator.processPending();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(transfer.getNextAttemptAt()).isNotNull().isAfter(Instant.now());
        assertThat(transfer.getFailureReason()).isEqualTo("unreachable");
        verify(outboxService, never()).record(any(DomainEvent.class));
        verify(metrics).clientRetry("unavailable");
    }

    @Test
    void circuitOpen_usesLongerBackoffAndRecordsRetryMetric() {
        CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.getName()).thenReturn("accountService");
        when(circuitBreaker.getCircuitBreakerConfig()).thenReturn(CircuitBreakerConfig.ofDefaults());
        CallNotPermittedException circuitOpen =
                CallNotPermittedException.createCallNotPermittedException(circuitBreaker);
        stubTransfer(TransferStatus.PENDING);
        when(client.withdraw(any(UUID.class), any(UUID.class), any(BigDecimal.class),
                anyString(), anyString()))
                .thenThrow(circuitOpen);

        orchestrator.processPending();

        verify(metrics).clientRetry("circuit_open");
    }

    @Test
    void bulkheadFull_usesRetryBackoffAndRecordsRetryMetric() {
        ThreadPoolBulkhead bulkhead = mock(ThreadPoolBulkhead.class);
        when(bulkhead.getName()).thenReturn("accountService");
        when(bulkhead.getBulkheadConfig()).thenReturn(ThreadPoolBulkheadConfig.ofDefaults());
        BulkheadFullException bulkheadFull =
                BulkheadFullException.createBulkheadFullException(bulkhead);
        stubTransfer(TransferStatus.PENDING);
        when(client.withdraw(any(UUID.class), any(UUID.class), any(BigDecimal.class),
                anyString(), anyString()))
                .thenThrow(bulkheadFull);

        orchestrator.processPending();

        verify(metrics).clientRetry("bulkhead_full");
    }

    private Transfer stubTransfer(TransferStatus status) {
        Transfer transfer = Transfer.initiate(
                CUSTOMER_ID, SOURCE, TARGET,
                Money.of(AMOUNT, "EUR"), "TRX-1", CORRELATION
        );
        ReflectionTestUtils.setField(transfer, "id", TRANSFER_ID);
        ReflectionTestUtils.setField(transfer, "status", status);
        when(repository.findById(TRANSFER_ID)).thenReturn(Optional.of(transfer));
        return transfer;
    }

    private DomainEvent capturedEvent() {
        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxService).record(captor.capture());
        return captor.getValue();
    }

    private AccountServiceException businessFailure() {
        return new AccountServiceException(
                HttpStatus.UNPROCESSABLE_CONTENT, SOURCE, "Unprocessable Entity", "{}"
        );
    }
}
