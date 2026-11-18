package com.banco.account_service.account.service;

import com.banco.account_service.account.Account;
import com.banco.account_service.account.dto.AccountResponse;
import com.banco.account_service.account.exception.AccountAlreadyExistsException;
import com.banco.account_service.account.exception.AccountNotFoundException;
import com.banco.account_service.account.mapper.AccountMapper;
import com.banco.account_service.account.repository.AccountRepository;
import com.banco.account_service.outbox.OutboxService;
import com.banco.account_service.outbox.event.AccountOpenedEvent;
import com.banco.account_service.security.CustomerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;
    private final AccountMapper accountMapper;
    private final OutboxService outboxService;

    @Override
    @Transactional
    public AccountResponse openAccount(
            UUID customerId,
            String number,
            String currency
    ) {
        if (accountRepository.existsByNumber(number)) {
            throw new AccountAlreadyExistsException(number);
        }

        Account account = Account.open(customerId, number, currency);

        try {
            Account savedAccount = accountRepository.saveAndFlush(account);
            outboxService.record("ACCOUNT", new AccountOpenedEvent(
                    UUID.randomUUID(),
                    savedAccount.getId(),
                    customerId,
                    savedAccount.getNumber(),
                    savedAccount.getCurrency(),
                    savedAccount.getCreatedAt(),
                    "ACCOUNT_OPENED"
            ));
            return accountMapper.toResponse(savedAccount);
        } catch (DataIntegrityViolationException ex) {
            throw new AccountAlreadyExistsException(number);
        }
    }

    @Override
    public AccountResponse getAccountById(UUID accountId, CustomerContext customer) {
        Account account = customer.admin()
                ? accountRepository.findById(accountId)
                        .orElseThrow(() -> new AccountNotFoundException(accountId))
                : accountRepository.findByIdAndCustomerId(accountId, customer.customerId())
                        .orElseThrow(() -> new AccountNotFoundException(accountId));

        return accountMapper.toResponse(account);
    }
}
