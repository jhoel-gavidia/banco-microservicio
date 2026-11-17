package com.banco.account_service.ledger.service;

import com.banco.account_service.account.Account;
import com.banco.account_service.account.exception.AccountNotFoundException;
import com.banco.account_service.account.repository.AccountRepository;
import com.banco.account_service.ledger.LedgerEntry;
import com.banco.account_service.ledger.LedgerEntryType;
import com.banco.account_service.ledger.dto.LedgerEntryResponse;
import com.banco.account_service.ledger.exception.DuplicateReferenceException;
import com.banco.account_service.ledger.mapper.LedgerEntryMapper;
import com.banco.account_service.ledger.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LedgerServiceImpl implements LedgerService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerEntryMapper ledgerEntryMapper;

    @Override
    @Transactional
    public LedgerEntryResponse deposit(UUID accountId, BigDecimal amount, String reference) {
        return recordMovement(accountId, amount, reference, LedgerEntryType.CREDIT);
    }

    @Override
    @Transactional
    public LedgerEntryResponse withdraw(UUID accountId, BigDecimal amount, String reference) {
        return recordMovement(accountId, amount, reference, LedgerEntryType.DEBIT);
    }

    private LedgerEntryResponse recordMovement(
            UUID accountId,
            BigDecimal amount,
            String reference,
            LedgerEntryType type
    ) {
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        if (ledgerEntryRepository.existsByAccountIdAndReferenceAndType(accountId, reference, type)) {
            throw new DuplicateReferenceException(accountId, reference, type);
        }

        BigDecimal balanceAfter = type == LedgerEntryType.CREDIT
                ? account.credit(amount)
                : account.debit(amount);

        LedgerEntry entry = type == LedgerEntryType.CREDIT
                ? LedgerEntry.credit(account, amount, balanceAfter, reference)
                : LedgerEntry.debit(account, amount, balanceAfter, reference);

        try {
            return ledgerEntryMapper.toResponse(ledgerEntryRepository.saveAndFlush(entry));
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateReferenceException(accountId, reference, type);
        }
    }

    @Override
    public List<LedgerEntryResponse> getMovements(UUID accountId) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }

        return ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(ledgerEntryMapper::toResponse)
                .toList();
    }
}
