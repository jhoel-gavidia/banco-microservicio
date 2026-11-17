package com.banco.account_service.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKeyId implements Serializable {

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    public IdempotencyKeyId(UUID customerId, UUID accountId, String idempotencyKey) {
        this.customerId = customerId;
        this.accountId = accountId;
        this.idempotencyKey = idempotencyKey;
    }
}
