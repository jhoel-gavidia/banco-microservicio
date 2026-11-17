package com.banco.account_service.account.service;

import com.banco.account_service.account.dto.AccountResponse;

import java.util.UUID;

public interface AccountService {

    AccountResponse openAccount(
            UUID customerId,
            String number,
            String currency
    );

    AccountResponse getAccountById(UUID accountId);

}
