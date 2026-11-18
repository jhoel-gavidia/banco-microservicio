package com.banco.transfer_service.idempotency.exception;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key already used with a different request: " + key);
    }
}
