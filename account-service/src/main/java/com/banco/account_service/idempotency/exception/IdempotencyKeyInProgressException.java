package com.banco.account_service.idempotency.exception;

public class IdempotencyKeyInProgressException extends RuntimeException {

    public IdempotencyKeyInProgressException(String key) {
        super("A request with this Idempotency-Key is already in progress: " + key);
    }
}
