package com.banco.transfer_service.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, IdempotencyKeyId> {

    @Modifying
    @Query(value = """
            INSERT INTO idempotency_keys
                (customer_id, idempotency_key, request_hash, status)
            VALUES
                (:customerId, :key, :requestHash, 'IN_PROGRESS')
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("customerId") UUID customerId,
            @Param("key") String key,
            @Param("requestHash") String requestHash
    );
}
