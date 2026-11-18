package com.banco.transfer_service.client;

import com.banco.transfer_service.client.dto.AccountInfo;
import com.banco.transfer_service.client.dto.AccountMovementInfo;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Component
public class AccountServiceClientImpl implements AccountServiceClient {

    private static final String CUSTOMER_HEADER = "X-Customer-Id";
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final RestClient restClient;

    public AccountServiceClientImpl(
            @Qualifier("accountServiceRestClient") RestClient restClient
    ) {
        this.restClient = restClient;
    }

    @Override
    public AccountInfo getAccount(UUID accountId, UUID customerId) {
        try {
            AccountInfo account = restClient.get()
                    .uri("/api/v1/accounts/{accountId}", accountId)
                    .header(CUSTOMER_HEADER, customerId.toString())
                    .retrieve()
                    .body(AccountInfo.class);

            if (account == null) {
                throw new IllegalStateException("Empty response from account-service");
            }
            return account;
        } catch (RestClientResponseException ex) {
            throw translate(ex, accountId);
        }
    }

    @Override
    public AccountMovementInfo withdraw(
            UUID accountId,
            UUID customerId,
            BigDecimal amount,
            String reference,
            String idempotencyKey
    ) {
        return move("withdrawals", accountId, customerId, amount, reference, idempotencyKey);
    }

    @Override
    public AccountMovementInfo deposit(
            UUID accountId,
            UUID customerId,
            BigDecimal amount,
            String reference,
            String idempotencyKey
    ) {
        return move("deposits", accountId, customerId, amount, reference, idempotencyKey);
    }

    private AccountMovementInfo move(
            String operation,
            UUID accountId,
            UUID customerId,
            BigDecimal amount,
            String reference,
            String idempotencyKey
    ) {
        Map<String, Object> body = Map.of(
                "amount", amount,
                "reference", reference
        );

        try {
            AccountMovementInfo movement = restClient.post()
                    .uri("/api/v1/accounts/{accountId}/{operation}", accountId, operation)
                    .header(CUSTOMER_HEADER, customerId.toString())
                    .header(IDEMPOTENCY_HEADER, idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(AccountMovementInfo.class);

            if (movement == null) {
                throw new IllegalStateException("Empty response from account-service");
            }
            return movement;
        } catch (RestClientResponseException ex) {
            throw translate(ex, accountId);
        }
    }

    private AccountServiceException translate(
            RestClientResponseException ex,
            UUID accountId
    ) {
        return new AccountServiceException(
                ex.getStatusCode(),
                accountId,
                ex.getStatusText(),
                ex.getResponseBodyAsString()
        );
    }
}
