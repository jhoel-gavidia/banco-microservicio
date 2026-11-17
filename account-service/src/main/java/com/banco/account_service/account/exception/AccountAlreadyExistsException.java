package com.banco.account_service.account.exception;

public class AccountAlreadyExistsException extends RuntimeException {

    public AccountAlreadyExistsException(String number) {
        super("Account number already exists: " + number);
    }
}
