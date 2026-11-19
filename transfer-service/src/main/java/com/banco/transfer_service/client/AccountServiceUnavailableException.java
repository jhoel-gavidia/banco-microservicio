package com.banco.transfer_service.client;

public class AccountServiceUnavailableException extends RuntimeException {

    public AccountServiceUnavailableException(String message) {
        super(message);
    }

    public AccountServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
