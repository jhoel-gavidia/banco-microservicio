package com.banco.account_service.ledger.service;

import com.banco.account_service.account.Account;
import com.banco.account_service.account.exception.AccountNotActiveException;
import com.banco.account_service.account.exception.InsufficientFundsException;
import com.banco.account_service.account.repository.AccountRepository;
import com.banco.account_service.correlation.CorrelationContext;
import com.banco.account_service.idempotency.IdempotencyService;
import com.banco.account_service.ledger.LedgerEntry;
import com.banco.account_service.ledger.LedgerEntryType;
import com.banco.account_service.ledger.dto.LedgerEntryResponse;
import com.banco.account_service.ledger.exception.DuplicateReferenceException;
import com.banco.account_service.ledger.mapper.LedgerEntryMapper;
import com.banco.account_service.ledger.repository.LedgerEntryRepository;
import com.banco.account_service.metrics.AccountMetrics;
import com.banco.account_service.outbox.DomainEvent;
import com.banco.account_service.outbox.OutboxService;
import com.banco.account_service.outbox.event.AccountMovementEvent;
import com.banco.account_service.security.CustomerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerServiceImplTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final String NUMBER = "LEDGER-001";
    private static final String REFERENCE = "TRX-123";
    private static final String KEY = "idem-123";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private LedgerEntryMapper ledgerEntryMapper;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private OutboxService outboxService;

    @Mock
    private AccountMetrics metrics;

    @InjectMocks
    private LedgerServiceImpl ledgerService;

    private final CustomerContext customer = new CustomerContext(CUSTOMER_ID, false, false);

    @AfterEach
    void tearDown() {
        CorrelationContext.clear();
    }

    @Test
    void deposit_recordsMovementOutboxAndIdempotentResponse() throws Exception {
        Account account = activeAccount();
        stubOwnerLookup(account);
        when(idempotencyService.begin(any(UUID.class), eq(ACCOUNT_ID), eq(KEY), anyString()))
                .thenReturn(Optional.empty());
        when(ledgerEntryRepository.existsByAccountIdAndReferenceAndType(
                ACCOUNT_ID, REFERENCE, LedgerEntryType.CREDIT
        )).thenReturn(false);
        when(ledgerEntryRepository.saveAndFlush(any(LedgerEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(objectMapper.writeValueAsString(any(LedgerEntryResponse.class)))
                .thenReturn("stored-response");
        stubMapper();
        CorrelationContext.set("corr-ledger");

        LedgerEntryResponse response = ledgerService.deposit(
                ACCOUNT_ID, new BigDecimal("25.00"), REFERENCE, KEY, customer
        );

        assertThat(response.amount()).isEqualByComparingTo("25.00");
        assertThat(response.balanceAfter()).isEqualByComparingTo("25.00");
        assertThat(account.getBalance()).isEqualByComparingTo("25.00");

        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxService).record(eq("ACCOUNT"), event.capture());
        assertThat(event.getValue()).isInstanceOf(AccountMovementEvent.class);
        AccountMovementEvent movement = (AccountMovementEvent) event.getValue();
        assertThat(movement.type()).isEqualTo(LedgerEntryType.CREDIT);
        assertThat(movement.amount()).isEqualByComparingTo("25.00");
        assertThat(movement.balanceAfter()).isEqualByComparingTo("25.00");
        assertThat(movement.reference()).isEqualTo(REFERENCE);
        assertThat(movement.correlationId()).isEqualTo("corr-ledger");

        verify(idempotencyService).complete(CUSTOMER_ID, ACCOUNT_ID, KEY, 201, "stored-response");
        verify(metrics).movement(LedgerEntryType.CREDIT);
    }

    @Test
    void withdraw_replaysStoredResponseWhenIdempotencyKeyWasAlreadyCompleted() throws Exception {
        Account account = activeAccount();
        account.credit(new BigDecimal("50.00"));
        stubOwnerLookup(account);
        LedgerEntryResponse stored = response(LedgerEntryType.DEBIT, new BigDecimal("10.00"));
        when(idempotencyService.begin(any(UUID.class), eq(ACCOUNT_ID), eq(KEY), anyString()))
                .thenReturn(Optional.of(new IdempotencyService.StoredResponse(201, "stored-body")));
        when(objectMapper.readValue("stored-body", LedgerEntryResponse.class)).thenReturn(stored);

        LedgerEntryResponse response = ledgerService.withdraw(
                ACCOUNT_ID, new BigDecimal("10.00"), REFERENCE, KEY, customer
        );

        assertThat(response).isEqualTo(stored);
        assertThat(account.getBalance()).isEqualByComparingTo("50.00");
        verify(ledgerEntryRepository, never()).saveAndFlush(any(LedgerEntry.class));
        verify(outboxService, never()).record(anyString(), any(DomainEvent.class));
        verify(idempotencyService, never())
                .complete(any(UUID.class), any(UUID.class), anyString(), anyInt(), anyString());
        verify(metrics, never()).movement(any(LedgerEntryType.class));
    }

    @Test
    void withdraw_recordsDebitMovement() {
        Account account = activeAccount();
        account.credit(new BigDecimal("50.00"));
        stubOwnerLookup(account);
        when(ledgerEntryRepository.existsByAccountIdAndReferenceAndType(
                ACCOUNT_ID, REFERENCE, LedgerEntryType.DEBIT
        )).thenReturn(false);
        when(ledgerEntryRepository.saveAndFlush(any(LedgerEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        stubMapper();

        LedgerEntryResponse response = ledgerService.withdraw(
                ACCOUNT_ID, new BigDecimal("20.00"), REFERENCE, null, customer
        );

        assertThat(response.type()).isEqualTo(LedgerEntryType.DEBIT);
        assertThat(response.balanceAfter()).isEqualByComparingTo("30.00");
        assertThat(account.getBalance()).isEqualByComparingTo("30.00");
        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxService).record(eq("ACCOUNT"), event.capture());
        assertThat(((AccountMovementEvent) event.getValue()).type())
                .isEqualTo(LedgerEntryType.DEBIT);
    }

    @Test
    void deposit_whenReferenceIsDuplicated_throwsDuplicateReference() {
        Account account = activeAccount();
        stubOwnerLookup(account);
        when(ledgerEntryRepository.existsByAccountIdAndReferenceAndType(
                ACCOUNT_ID, REFERENCE, LedgerEntryType.CREDIT
        )).thenReturn(true);

        assertThatThrownBy(() -> ledgerService.deposit(
                ACCOUNT_ID, new BigDecimal("5.00"), REFERENCE, null, customer
        ))
                .isInstanceOf(DuplicateReferenceException.class)
                .hasMessageContaining(REFERENCE);

        verify(ledgerEntryRepository, never()).saveAndFlush(any(LedgerEntry.class));
        verify(outboxService, never()).record(anyString(), any(DomainEvent.class));
    }

    @Test
    void deposit_whenUniqueConstraintIsViolated_translatesException() {
        Account account = activeAccount();
        stubOwnerLookup(account);
        when(ledgerEntryRepository.existsByAccountIdAndReferenceAndType(
                ACCOUNT_ID, REFERENCE, LedgerEntryType.CREDIT
        )).thenReturn(false);
        when(ledgerEntryRepository.saveAndFlush(any(LedgerEntry.class)))
                .thenThrow(new DataIntegrityViolationException("uq_ledger"));

        assertThatThrownBy(() -> ledgerService.deposit(
                ACCOUNT_ID, new BigDecimal("5.00"), REFERENCE, null, customer
        ))
                .isInstanceOf(DuplicateReferenceException.class);

        verify(outboxService, never()).record(anyString(), any(DomainEvent.class));
    }

    @Test
    void withdraw_withoutFunds_throwsInsufficientFundsAndDoesNotRecordMovement() {
        Account account = activeAccount();
        account.credit(new BigDecimal("10.00"));
        stubOwnerLookup(account);

        assertThatThrownBy(() -> ledgerService.withdraw(
                ACCOUNT_ID, new BigDecimal("10.01"), REFERENCE, null, customer
        ))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(account.getBalance()).isEqualByComparingTo("10.00");
        verify(ledgerEntryRepository, never()).saveAndFlush(any(LedgerEntry.class));
        verify(outboxService, never()).record(anyString(), any(DomainEvent.class));
    }

    @Test
    void deposit_whenAccountIsNotActive_throwsAccountNotActive() {
        Account account = activeAccount();
        ReflectionTestUtils.setField(account, "status",
                com.banco.account_service.account.Status.BLOCKED);
        stubOwnerLookup(account);

        assertThatThrownBy(() -> ledgerService.deposit(
                ACCOUNT_ID, BigDecimal.ONE, REFERENCE, null, customer
        ))
                .isInstanceOf(AccountNotActiveException.class);

        verify(ledgerEntryRepository, never())
                .existsByAccountIdAndReferenceAndType(any(UUID.class), anyString(), any());
    }

    @Test
    void deposit_asAdmin_usesUnscopedLookup() {
        Account account = activeAccount();
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(ledgerEntryRepository.existsByAccountIdAndReferenceAndType(
                ACCOUNT_ID, REFERENCE, LedgerEntryType.CREDIT
        )).thenReturn(false);
        when(ledgerEntryRepository.saveAndFlush(any(LedgerEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        stubMapper();

        ledgerService.deposit(
                ACCOUNT_ID, BigDecimal.ONE, REFERENCE, null,
                new CustomerContext(CUSTOMER_ID, true, false)
        );

        verify(accountRepository).findByIdForUpdate(ACCOUNT_ID);
        verify(accountRepository, never())
                .findByIdForUpdateAndCustomerId(any(UUID.class), any(UUID.class));
    }

    @Test
    void getMovements_asOwner_returnsMappedEntries() {
        Account account = activeAccount();
        when(accountRepository.findByIdAndCustomerId(ACCOUNT_ID, CUSTOMER_ID))
                .thenReturn(Optional.of(account));
        LedgerEntry entry = LedgerEntry.credit(account, BigDecimal.ONE, BigDecimal.ONE, REFERENCE);
        when(ledgerEntryRepository.findByAccountIdOrderByEntrySeqDesc(ACCOUNT_ID))
                .thenReturn(List.of(entry));
        when(ledgerEntryMapper.toResponse(entry)).thenReturn(response(
                LedgerEntryType.CREDIT, BigDecimal.ONE
        ));

        List<LedgerEntryResponse> movements = ledgerService.getMovements(ACCOUNT_ID, customer);

        assertThat(movements).hasSize(1);
        assertThat(movements.getFirst().reference()).isEqualTo(REFERENCE);
    }

    @Test
    void getMovements_asAdmin_usesUnscopedOwnershipCheck() {
        when(accountRepository.existsById(ACCOUNT_ID)).thenReturn(true);
        when(ledgerEntryRepository.findByAccountIdOrderByEntrySeqDesc(ACCOUNT_ID))
                .thenReturn(List.of());

        ledgerService.getMovements(ACCOUNT_ID, new CustomerContext(CUSTOMER_ID, true, false));

        verify(accountRepository).existsById(ACCOUNT_ID);
        verify(accountRepository, never()).findByIdAndCustomerId(any(UUID.class), any(UUID.class));
    }

    @Test
    void getMovements_whenAccountIsNotOwned_throwsAccountNotFound() {
        when(accountRepository.findByIdAndCustomerId(ACCOUNT_ID, CUSTOMER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> ledgerService.getMovements(ACCOUNT_ID, customer))
                .isInstanceOf(com.banco.account_service.account.exception.AccountNotFoundException.class);

        verify(ledgerEntryRepository, never())
                .findByAccountIdOrderByEntrySeqDesc(any(UUID.class));
    }

    private Account activeAccount() {
        Account account = Account.open(CUSTOMER_ID, NUMBER, "EUR");
        ReflectionTestUtils.setField(account, "id", ACCOUNT_ID);
        return account;
    }

    private void stubOwnerLookup(Account account) {
        when(accountRepository.findByIdForUpdateAndCustomerId(ACCOUNT_ID, CUSTOMER_ID))
                .thenReturn(Optional.of(account));
    }

    private void stubMapper() {
        when(ledgerEntryMapper.toResponse(any(LedgerEntry.class))).thenAnswer(invocation -> {
            LedgerEntry entry = invocation.getArgument(0);
            return new LedgerEntryResponse(
                    UUID.randomUUID(), ACCOUNT_ID, entry.getType(), entry.getAmount(),
                    entry.getBalanceAfter(), entry.getReference(), Instant.now()
            );
        });
    }

    private LedgerEntryResponse response(LedgerEntryType type, BigDecimal amount) {
        return new LedgerEntryResponse(
                UUID.randomUUID(), ACCOUNT_ID, type, amount, amount, REFERENCE, Instant.now()
        );
    }
}
