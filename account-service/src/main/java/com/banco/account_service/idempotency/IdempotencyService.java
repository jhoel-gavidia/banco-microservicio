package com.banco.account_service.idempotency;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyService {

    record StoredResponse(int status, String body) {
    }

    Optional<StoredResponse> begin(UUID customerId, String key, String requestHash);

    void complete(UUID customerId, String key, int status, String body);
}
