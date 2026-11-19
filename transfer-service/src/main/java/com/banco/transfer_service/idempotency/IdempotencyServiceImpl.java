package com.banco.transfer_service.idempotency;

import com.banco.transfer_service.idempotency.exception.IdempotencyKeyInProgressException;
import com.banco.transfer_service.idempotency.exception.IdempotencyKeyReusedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IdempotencyServiceImpl implements IdempotencyService {

    private final IdempotencyKeyRepository repository;

    @Override
    public Optional<StoredResponse> begin(UUID customerId, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new IllegalArgumentException("Idempotency-Key must be between 1 and 100 characters");
        }
        if (requestHash == null || requestHash.length() != 64) {
            throw new IllegalArgumentException("Invalid request hash");
        }

        IdempotencyKeyId id = new IdempotencyKeyId(customerId, key);
        Optional<IdempotencyKey> found = repository.findById(id);

        if (found.isEmpty()) {
            int inserted = repository.insertIfAbsent(customerId, key, requestHash);
            if (inserted > 0) {
                return Optional.empty();
            }
            found = repository.findById(id);
        }

        IdempotencyKey existing = found.orElseThrow(() ->
                new IllegalStateException("Idempotency key disappeared: " + key)
        );

        if (!existing.matches(requestHash)) {
            throw new IdempotencyKeyReusedException(key);
        }
        if (existing.getStatus() == IdempotencyStatus.IN_PROGRESS) {
            throw new IdempotencyKeyInProgressException(key);
        }

        return Optional.of(new StoredResponse(
                existing.getResponseStatus(), existing.getResponseBody()
        ));
    }

    @Override
    public void complete(UUID customerId, String key, int status, String body) {
        IdempotencyKey stored = repository.findById(new IdempotencyKeyId(customerId, key))
                .orElseThrow(() -> new IllegalStateException("Idempotency key not found: " + key));
        stored.complete(status, body);
    }
}
