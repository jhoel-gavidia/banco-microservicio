package com.banco.account_service.account.exception;

import com.banco.account_service.account.Status;
import lombok.Getter;

import java.util.UUID;

@Getter
public class AccountNotActiveException extends RuntimeException {

    private final UUID accountId;
    private final Status status;

    public AccountNotActiveException(UUID accountId, Status status) {
        super("Account " + accountId + " is not active: " + status);
        this.accountId = accountId;
        this.status = status;
    }
}
