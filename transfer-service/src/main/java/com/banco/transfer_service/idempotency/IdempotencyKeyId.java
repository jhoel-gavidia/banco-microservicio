package com.banco.transfer_service.idempotency;

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

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    public IdempotencyKeyId(UUID customerId, String idempotencyKey) {
        this.customerId = customerId;
        this.idempotencyKey = idempotencyKey;
    }
}
