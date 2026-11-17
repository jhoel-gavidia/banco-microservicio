package com.banco.account_service.idempotency;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyService {

    record StoredResponse(int status, String body) {
    }

    Optional<StoredResponse> begin(
            UUID customerId,
            UUID accountId,
            String key,
            String requestHash
    );

    void complete(UUID customerId, UUID accountId, String key, int status, String body);
}
