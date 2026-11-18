package com.banco.transfer_service.client;

import org.springframework.http.HttpStatusCode;

import java.util.UUID;

public class AccountServiceException extends RuntimeException {

    private final HttpStatusCode status;
    private final UUID accountId;
    private final String responseBody;

    public AccountServiceException(
            HttpStatusCode status,
            UUID accountId,
            String reason,
            String responseBody
    ) {
        super("account-service returned " + status.value() + " for account "
                + accountId + ": " + reason);
        this.status = status;
        this.accountId = accountId;
        this.responseBody = responseBody;
    }

    public HttpStatusCode getStatus() {
        return status;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
