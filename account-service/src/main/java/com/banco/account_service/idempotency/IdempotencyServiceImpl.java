package com.banco.account_service.idempotency;

import com.banco.account_service.idempotency.exception.IdempotencyKeyInProgressException;
import com.banco.account_service.idempotency.exception.IdempotencyKeyReusedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IdempotencyServiceImpl implements IdempotencyService {

    private final IdempotencyKeyRepository repository;

    @Override
    public Optional<StoredResponse> begin(
            UUID customerId,
            UUID accountId,
            String key,
            String requestHash
    ) {
        if (key.length() > 100) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 100 characters");
        }

        IdempotencyKeyId id = new IdempotencyKeyId(customerId, accountId, key);
        Optional<IdempotencyKey> found = repository.findById(id);

        if (found.isEmpty()) {
            int inserted = repository.insertIfAbsent(customerId, accountId, key, requestHash);
            if (inserted > 0) {
                return Optional.empty();
            }

            found = repository.findById(id);
        }

        IdempotencyKey existing = found.orElseThrow(() ->
                new IllegalStateException("Idempotency key disappeared: " + key));

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
    public void complete(UUID customerId, UUID accountId, String key, int status, String body) {
        IdempotencyKey stored = repository.findById(
                new IdempotencyKeyId(customerId, accountId, key)
        )
                .orElseThrow(() -> new IllegalStateException("Idempotency key not found: " + key));
        stored.complete(status, body);
    }
}
