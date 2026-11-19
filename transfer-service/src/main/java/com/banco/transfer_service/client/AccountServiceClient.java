package com.banco.transfer_service.client;

import com.banco.transfer_service.client.dto.AccountInfo;
import com.banco.transfer_service.client.dto.AccountMovementInfo;

import java.math.BigDecimal;
import java.util.UUID;

public interface AccountServiceClient {

    AccountInfo getAccount(UUID accountId, UUID customerId);

    AccountMovementInfo withdraw(
            UUID accountId,
            UUID customerId,
            BigDecimal amount,
            String reference,
            String idempotencyKey
    );

    AccountMovementInfo deposit(
            UUID accountId,
            UUID customerId,
            BigDecimal amount,
            String reference,
            String idempotencyKey
    );
}
