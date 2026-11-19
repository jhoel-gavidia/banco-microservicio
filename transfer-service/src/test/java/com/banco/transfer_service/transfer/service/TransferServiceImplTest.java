package com.banco.transfer_service.transfer.service;

import com.banco.transfer_service.client.AccountServiceClient;
import com.banco.transfer_service.client.AccountServiceException;
import com.banco.transfer_service.client.dto.AccountInfo;
import com.banco.transfer_service.correlation.CorrelationContext;
import com.banco.transfer_service.idempotency.IdempotencyService;
import com.banco.transfer_service.metrics.TransferMetrics;
import com.banco.transfer_service.outbox.DomainEvent;
import com.banco.transfer_service.outbox.OutboxService;
import com.banco.transfer_service.outbox.event.TransferInitiatedEvent;
import com.banco.transfer_service.security.CustomerContext;
import com.banco.transfer_service.transfer.domain.Money;
import com.banco.transfer_service.transfer.domain.Transfer;
import com.banco.transfer_service.transfer.domain.TransferStatus;
import com.banco.transfer_service.transfer.dto.CreateTransferRequest;
import com.banco.transfer_service.transfer.dto.TransferResponse;
import com.banco.transfer_service.transfer.exception.TransferNotFoundException;
import com.banco.transfer_service.transfer.mapper.TransferMapper;
import com.banco.transfer_service.transfer.repository.TransferRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceImplTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID TRANSFER_ID = UUID.randomUUID();
    private static final String KEY = "idem-transfer";

    @Mock
    private TransferRepository repository;

    @Mock
    private TransferMapper mapper;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private AccountServiceClient accountServiceClient;

    @Mock
    private OutboxService outboxService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private TransferMetrics metrics;

    @InjectMocks
    private TransferServiceImpl transferService;

    private final CustomerContext customer = new CustomerContext(CUSTOMER_ID, false);

    @AfterEach
    void tearDown() {
        CorrelationContext.clear();
    }

    @Test
    void create_persistsTransferRecordsOutboxAndCompletesIdempotency() throws Exception {
        CorrelationContext.set("corr-create");
        stubClient();
        when(idempotencyService.begin(eq(CUSTOMER_ID), eq(KEY), anyString()))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(Transfer.class))).thenAnswer(invocation -> {
            Transfer transfer = invocation.getArgument(0);
            ReflectionTestUtils.setField(transfer, "id", TRANSFER_ID);
            return transfer;
        });
        TransferResponse response = response(TRANSFER_ID);
        when(mapper.toResponse(any(Transfer.class))).thenReturn(response);
        when(objectMapper.writeValueAsString(response)).thenReturn("stored-body");

        TransferResponse result = transferService.create(request(), customer, KEY);

        assertThat(result).isEqualTo(response);
        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxService).record(event.capture());
        assertThat(event.getValue()).isInstanceOf(TransferInitiatedEvent.class);
        TransferInitiatedEvent initiated = (TransferInitiatedEvent) event.getValue();
        assertThat(initiated.transferId()).isEqualTo(TRANSFER_ID);
        assertThat(initiated.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(initiated.sourceAccountId()).isEqualTo(SOURCE);
        assertThat(initiated.targetAccountId()).isEqualTo(TARGET);
        assertThat(initiated.amount()).isEqualByComparingTo("25.00");
        assertThat(initiated.currency()).isEqualTo("EUR");
        assertThat(initiated.eventType()).isEqualTo("TransferInitiated");
        assertThat(initiated.correlationId()).isEqualTo("corr-create");

        verify(idempotencyService).complete(CUSTOMER_ID, KEY, 201, "stored-body");
        verify(metrics).created();
    }

    @Test
    void create_whenKeyWasCompleted_replaysStoredResponse() throws Exception {
        stubClient();
        TransferResponse stored = response(TRANSFER_ID);
        when(idempotencyService.begin(eq(CUSTOMER_ID), eq(KEY), anyString()))
                .thenReturn(Optional.of(new IdempotencyService.StoredResponse(201, "stored-body")));
        when(objectMapper.readValue("stored-body", TransferResponse.class)).thenReturn(stored);

        TransferResponse result = transferService.create(request(), customer, KEY);

        assertThat(result).isEqualTo(stored);
        verify(repository, never()).saveAndFlush(any(Transfer.class));
        verify(outboxService, never()).record(any(DomainEvent.class));
        verify(idempotencyService, never())
                .complete(any(UUID.class), anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString());
        verify(metrics, never()).created();
    }

    @Test
    void create_withoutIdempotencyKey_throwsIllegalArgument() {
        assertThatThrownBy(() -> transferService.create(request(), customer, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key header is required");

        verify(accountServiceClient, never()).getAccount(any(UUID.class), any(UUID.class));
        verify(repository, never()).saveAndFlush(any(Transfer.class));
    }

    @Test
    void create_whenSourceAccountIsNotOwned_propagatesAccountServiceError() {
        when(accountServiceClient.getAccount(SOURCE, CUSTOMER_ID)).thenThrow(
                new AccountServiceException(
                        HttpStatus.NOT_FOUND, SOURCE, "Not Found", "{\"detail\":\"missing\"}"
                )
        );

        assertThatThrownBy(() -> transferService.create(request(), customer, KEY))
                .isInstanceOf(AccountServiceException.class)
                .hasMessageContaining("404");

        verify(idempotencyService, never()).begin(any(UUID.class), anyString(), anyString());
        verify(repository, never()).saveAndFlush(any(Transfer.class));
    }

    @Test
    void getById_asOwner_usesScopedLookup() {
        Transfer transfer = transfer();
        when(repository.findByIdAndCustomerId(TRANSFER_ID, CUSTOMER_ID))
                .thenReturn(Optional.of(transfer));
        when(mapper.toResponse(transfer)).thenReturn(response(TRANSFER_ID));

        assertThat(transferService.getById(TRANSFER_ID, customer).id()).isEqualTo(TRANSFER_ID);

        verify(repository).findByIdAndCustomerId(TRANSFER_ID, CUSTOMER_ID);
        verify(repository, never()).findById(any(UUID.class));
    }

    @Test
    void getById_asAdmin_usesUnscopedLookup() {
        Transfer transfer = transfer();
        when(repository.findById(TRANSFER_ID)).thenReturn(Optional.of(transfer));
        when(mapper.toResponse(transfer)).thenReturn(response(TRANSFER_ID));

        transferService.getById(TRANSFER_ID, new CustomerContext(CUSTOMER_ID, true));

        verify(repository).findById(TRANSFER_ID);
        verify(repository, never()).findByIdAndCustomerId(any(UUID.class), any(UUID.class));
    }

    @Test
    void getById_whenMissing_throwsTransferNotFound() {
        when(repository.findByIdAndCustomerId(TRANSFER_ID, CUSTOMER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.getById(TRANSFER_ID, customer))
                .isInstanceOf(TransferNotFoundException.class)
                .hasMessageContaining(TRANSFER_ID.toString());
    }

    @Test
    void list_asOwner_returnsOnlyCustomerTransfers() {
        Transfer transfer = transfer();
        when(repository.findAllByCustomerIdOrderByCreatedAtDesc(CUSTOMER_ID))
                .thenReturn(List.of(transfer));
        when(mapper.toResponse(transfer)).thenReturn(response(TRANSFER_ID));

        List<TransferResponse> result = transferService.list(customer);

        assertThat(result).hasSize(1);
        verify(repository).findAllByCustomerIdOrderByCreatedAtDesc(CUSTOMER_ID);
        verify(repository, never()).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void list_asAdmin_returnsAllTransfers() {
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        transferService.list(new CustomerContext(CUSTOMER_ID, true));

        verify(repository).findAllByOrderByCreatedAtDesc();
        verify(repository, never()).findAllByCustomerIdOrderByCreatedAtDesc(any(UUID.class));
    }

    private void stubClient() {
        when(accountServiceClient.getAccount(SOURCE, CUSTOMER_ID)).thenReturn(
                new AccountInfo(SOURCE, "ES91", "EUR", BigDecimal.TEN, "ACTIVE")
        );
    }

    private CreateTransferRequest request() {
        return new CreateTransferRequest(
                SOURCE, TARGET, new BigDecimal("25.00"), "EUR", "TRX-1"
        );
    }

    private Transfer transfer() {
        Transfer transfer = Transfer.initiate(
                CUSTOMER_ID, SOURCE, TARGET,
                Money.of(new BigDecimal("25.00"), "EUR"), "TRX-1", "corr-create"
        );
        ReflectionTestUtils.setField(transfer, "id", TRANSFER_ID);
        return transfer;
    }

    private TransferResponse response(UUID id) {
        return new TransferResponse(
                id, CUSTOMER_ID, SOURCE, TARGET, new BigDecimal("25.00"), "EUR",
                TransferStatus.PENDING, "TRX-1", Instant.now()
        );
    }
}
