package com.banco.transfer_service.client;

import com.banco.transfer_service.client.dto.AccountInfo;
import com.banco.transfer_service.client.dto.AccountMovementInfo;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
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
    private final AccountServiceResilience resilience;

    public AccountServiceClientImpl(
            @Qualifier("accountServiceRestClient") RestClient restClient,
            AccountServiceResilience resilience
    ) {
        this.restClient = restClient;
        this.resilience = resilience;
    }

    @Override
    public AccountInfo getAccount(UUID accountId, UUID customerId) {
        return resilience.call(() -> {
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
            } catch (ResourceAccessException ex) {
                throw unavailable(ex);
            }
        });
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

        return resilience.call(() -> {
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
            } catch (ResourceAccessException ex) {
                throw unavailable(ex);
            }
        });
    }

    private RuntimeException translate(RestClientResponseException ex, UUID accountId) {
        HttpStatusCode status = ex.getStatusCode();
        if (isTransient(status)) {
            return new AccountServiceUnavailableException(
                    "account-service returned " + status.value() + " for account "
                            + accountId + ": " + ex.getStatusText(),
                    ex
            );
        }
        return new AccountServiceException(
                status,
                accountId,
                ex.getStatusText(),
                ex.getResponseBodyAsString()
        );
    }

    private static boolean isTransient(HttpStatusCode status) {
        int value = status.value();
        return value == 408 || value == 429 || (value >= 500 && value < 600);
    }

    private static AccountServiceUnavailableException unavailable(ResourceAccessException ex) {
        return new AccountServiceUnavailableException(
                "account-service is unreachable: " + ex.getMessage(),
                ex
        );
    }
}
