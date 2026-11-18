package com.banco.account_service.ledger.service;

import com.banco.account_service.account.Account;
import com.banco.account_service.account.exception.AccountNotFoundException;
import com.banco.account_service.account.repository.AccountRepository;
import com.banco.account_service.idempotency.IdempotencyService;
import com.banco.account_service.idempotency.IdempotencyService.StoredResponse;
import com.banco.account_service.outbox.OutboxService;
import com.banco.account_service.outbox.event.AccountMovementEvent;
import com.banco.account_service.ledger.LedgerEntry;
import com.banco.account_service.ledger.LedgerEntryType;
import com.banco.account_service.ledger.dto.LedgerEntryResponse;
import com.banco.account_service.ledger.exception.DuplicateReferenceException;
import com.banco.account_service.ledger.mapper.LedgerEntryMapper;
import com.banco.account_service.ledger.repository.LedgerEntryRepository;
import com.banco.account_service.security.CustomerContext;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LedgerServiceImpl implements LedgerService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerEntryMapper ledgerEntryMapper;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final OutboxService outboxService;

    @Override
    @Transactional
    public LedgerEntryResponse deposit(
            UUID accountId,
            BigDecimal amount,
            String reference,
            String idempotencyKey,
            CustomerContext customer
    ) {
        return recordMovement(
                accountId, amount, reference, LedgerEntryType.CREDIT, idempotencyKey, customer
        );
    }

    @Override
    @Transactional
    public LedgerEntryResponse withdraw(
            UUID accountId,
            BigDecimal amount,
            String reference,
            String idempotencyKey,
            CustomerContext customer
    ) {
        return recordMovement(
                accountId, amount, reference, LedgerEntryType.DEBIT, idempotencyKey, customer
        );
    }

    private LedgerEntryResponse recordMovement(
            UUID accountId,
            BigDecimal amount,
            String reference,
            LedgerEntryType type,
            String idempotencyKey,
            CustomerContext customer
    ) {
        Account account = customer.admin()
                ? accountRepository.findByIdForUpdate(accountId)
                        .orElseThrow(() -> new AccountNotFoundException(accountId))
                : accountRepository.findByIdForUpdateAndCustomerId(accountId, customer.customerId())
                        .orElseThrow(() -> new AccountNotFoundException(accountId));

        account.requireActive();

        boolean idempotent = idempotencyKey != null && !idempotencyKey.isBlank();

        if (idempotent) {
            Optional<StoredResponse> replay = idempotencyService.begin(
                    account.getCustomerId(), accountId, idempotencyKey,
                hash(accountId, type, amount, reference)
            );
            if (replay.isPresent()) {
                return deserialize(replay.get().body());
            }
        }

        if (ledgerEntryRepository.existsByAccountIdAndReferenceAndType(accountId, reference, type)) {
            throw new DuplicateReferenceException(accountId, reference, type);
        }

        BigDecimal balanceAfter = type == LedgerEntryType.CREDIT
                ? account.credit(amount)
                : account.debit(amount);

        LedgerEntry entry = type == LedgerEntryType.CREDIT
                ? LedgerEntry.credit(account, amount, balanceAfter, reference)
                : LedgerEntry.debit(account, amount, balanceAfter, reference);

        LedgerEntryResponse response;
        try {
            LedgerEntry saved = ledgerEntryRepository.saveAndFlush(entry);
            outboxService.record("ACCOUNT", new AccountMovementEvent(
                    UUID.randomUUID(),
                    accountId,
                    account.getCustomerId(),
                    type,
                    amount,
                    balanceAfter,
                    reference,
                    saved.getCreatedAt()
            ));
            response = ledgerEntryMapper.toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateReferenceException(accountId, reference, type);
        }

        if (idempotent) {
            idempotencyService.complete(
                    account.getCustomerId(), accountId, idempotencyKey,
                    201, serialize(response)
            );
        }

        return response;
    }

    @Override
    public List<LedgerEntryResponse> getMovements(UUID accountId, CustomerContext customer) {
        boolean owned = customer.admin()
                ? accountRepository.existsById(accountId)
                : accountRepository.findByIdAndCustomerId(accountId, customer.customerId()).isPresent();

        if (!owned) {
            throw new AccountNotFoundException(accountId);
        }

        return ledgerEntryRepository.findByAccountIdOrderByEntrySeqDesc(accountId).stream()
                .map(ledgerEntryMapper::toResponse)
                .toList();
    }

    private static String hash(
            UUID accountId,
            LedgerEntryType type,
            BigDecimal amount,
            String reference
    ) {
        String payload = accountId + "|" + type.name() + "|"
                + amount.stripTrailingZeros().toPlainString() + "|" + reference;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private LedgerEntryResponse deserialize(String body) {
        try {
            return objectMapper.readValue(body, LedgerEntryResponse.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Stored idempotent response is unreadable", ex);
        }
    }

    private String serialize(LedgerEntryResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Could not store idempotent response", ex);
        }
    }
}
