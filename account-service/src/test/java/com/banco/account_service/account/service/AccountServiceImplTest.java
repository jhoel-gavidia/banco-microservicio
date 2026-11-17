package com.banco.account_service.account.service;

import com.banco.account_service.account.Account;
import com.banco.account_service.account.dto.AccountResponse;
import com.banco.account_service.account.exception.AccountAlreadyExistsException;
import com.banco.account_service.account.exception.AccountNotFoundException;
import com.banco.account_service.account.mapper.AccountMapper;
import com.banco.account_service.account.repository.AccountRepository;
import com.banco.account_service.security.CustomerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceImplTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountMapper accountMapper;

    @InjectMocks
    private AccountServiceImpl accountService;

    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final String NUMBER = "ES9121000418450200051332";
    private static final String CURRENCY = "EUR";

    private void stubMapper() {
        when(accountMapper.toResponse(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            return new AccountResponse(
                    account.getId(), account.getNumber(), account.getCurrency(),
                    account.getBalance(), account.getStatus(), account.getCreatedAt()
            );
        });
    }

    @Test
    void openAccount_savesAndReturnsResponse() {
        when(accountRepository.existsByNumber(NUMBER)).thenReturn(false);
        when(accountRepository.saveAndFlush(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            // Simula lo que haría JPA al persistir (id, version y created_at los genera la BD)
            return account;
        });
        stubMapper();

        AccountResponse response = accountService.openAccount(CUSTOMER_ID, NUMBER, CURRENCY);

        assertThat(response.number()).isEqualTo(NUMBER);
        assertThat(response.currency()).isEqualTo(CURRENCY);
        assertThat(response.balance()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(accountRepository).saveAndFlush(any(Account.class));
    }

    @Test
    void openAccount_whenNumberExists_throwsAccountAlreadyExists() {
        when(accountRepository.existsByNumber(NUMBER)).thenReturn(true);

        assertThatThrownBy(() -> accountService.openAccount(CUSTOMER_ID, NUMBER, CURRENCY))
                .isInstanceOf(AccountAlreadyExistsException.class)
                .hasMessageContaining(NUMBER);

        verify(accountRepository, never()).saveAndFlush(any(Account.class));
    }

    @Test
    void openAccount_whenUniqueConstraintIsViolated_translatesException() {
        when(accountRepository.existsByNumber(NUMBER)).thenReturn(false);
        when(accountRepository.saveAndFlush(any(Account.class)))
                .thenThrow(new DataIntegrityViolationException("uq_accounts_number"));

        assertThatThrownBy(() -> accountService.openAccount(CUSTOMER_ID, NUMBER, CURRENCY))
                .isInstanceOf(AccountAlreadyExistsException.class)
                .hasMessageContaining(NUMBER);
    }

    @Test
    void getAccountById_whenExists_returnsResponse() {
        Account account = Account.open(CUSTOMER_ID, NUMBER, CURRENCY);
        when(accountRepository.findByIdAndCustomerId(any(UUID.class), eq(CUSTOMER_ID)))
                .thenReturn(Optional.of(account));
        stubMapper();

        AccountResponse response = accountService.getAccountById(
                UUID.randomUUID(), new CustomerContext(CUSTOMER_ID, false)
        );

        assertThat(response.number()).isEqualTo(NUMBER);
    }

    @Test
    void getAccountById_whenMissing_throwsAccountNotFound() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findByIdAndCustomerId(accountId, CUSTOMER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccountById(
                accountId, new CustomerContext(CUSTOMER_ID, false)
        ))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining(accountId.toString());
    }
}
