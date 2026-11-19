package com.banco.transfer_service.client;

import com.banco.transfer_service.client.dto.AccountInfo;
import com.banco.transfer_service.client.dto.AccountMovementInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SuppressWarnings("unchecked")
class AccountServiceClientImplTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();

    private MockRestServiceServer server;
    private AccountServiceClientImpl client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.baseUrl("http://account-service").build();

        AccountServiceResilience resilience = mock(AccountServiceResilience.class);
        when(resilience.call(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get()
        );

        client = new AccountServiceClientImpl(restClient, resilience);
    }

    @Test
    void getAccount_sendsCustomerHeaderAndReturnsAccount() {
        server.expect(requestTo("http://account-service/api/v1/accounts/" + ACCOUNT_ID))
                .andExpect(header("X-Customer-Id", CUSTOMER_ID.toString()))
                .andRespond(withSuccess(
                        "{\"id\":\"" + ACCOUNT_ID + "\",\"number\":\"ES91\",\"currency\":\"EUR\","
                                + "\"balance\":\"10.00\",\"status\":\"ACTIVE\"}",
                        MediaType.APPLICATION_JSON
                ));

        AccountInfo account = client.getAccount(ACCOUNT_ID, CUSTOMER_ID);

        assertThat(account.id()).isEqualTo(ACCOUNT_ID);
        assertThat(account.currency()).isEqualTo("EUR");
        server.verify();
    }

    @Test
    void withdraw_sendsIdempotencyKeyAndMovementBody() {
        server.expect(requestTo("http://account-service/api/v1/accounts/" + ACCOUNT_ID + "/withdrawals"))
                .andExpect(header("X-Customer-Id", CUSTOMER_ID.toString()))
                .andExpect(header("Idempotency-Key", "transfer-1-debit"))
                .andExpect(jsonPath("$.amount").value(25.00))
                .andExpect(jsonPath("$.reference").value("TRX-1"))
                .andRespond(withSuccess(
                        "{\"id\":\"" + UUID.randomUUID() + "\",\"accountId\":\"" + ACCOUNT_ID
                                + "\",\"type\":\"DEBIT\",\"amount\":\"25.00\","
                                + "\"balanceAfter\":\"75.00\",\"reference\":\"TRX-1\"}",
                        MediaType.APPLICATION_JSON
                ));

        AccountMovementInfo movement = client.withdraw(
                ACCOUNT_ID, CUSTOMER_ID, new BigDecimal("25.00"), "TRX-1", "transfer-1-debit"
        );

        assertThat(movement.type()).isEqualTo("DEBIT");
        assertThat(movement.balanceAfter()).isEqualByComparingTo("75.00");
        server.verify();
    }

    @Test
    void deposit_sendsIdempotencyKeyAndMovementBody() {
        server.expect(requestTo("http://account-service/api/v1/accounts/" + ACCOUNT_ID + "/deposits"))
                .andExpect(header("Idempotency-Key", "transfer-1-credit"))
                .andRespond(withSuccess(
                        "{\"id\":\"" + UUID.randomUUID() + "\",\"accountId\":\"" + ACCOUNT_ID
                                + "\",\"type\":\"CREDIT\",\"amount\":\"25.00\","
                                + "\"balanceAfter\":\"125.00\",\"reference\":\"TRX-1\"}",
                        MediaType.APPLICATION_JSON
                ));

        AccountMovementInfo movement = client.deposit(
                ACCOUNT_ID, CUSTOMER_ID, new BigDecimal("25.00"), "TRX-1", "transfer-1-credit"
        );

        assertThat(movement.type()).isEqualTo("CREDIT");
        server.verify();
    }

    @Test
    void getAccount_whenBusinessError_returnsAccountServiceException() {
        server.expect(requestTo("http://account-service/api/v1/accounts/" + ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"detail\":\"not found\"}"));

        assertThatThrownBy(() -> client.getAccount(ACCOUNT_ID, CUSTOMER_ID))
                .isInstanceOf(AccountServiceException.class)
                .hasMessageContaining("404")
                .hasFieldOrPropertyWithValue("accountId", ACCOUNT_ID);
        server.verify();
    }

    @Test
    void getAccount_whenServerError_returnsUnavailableException() {
        server.expect(requestTo("http://account-service/api/v1/accounts/" + ACCOUNT_ID))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getAccount(ACCOUNT_ID, CUSTOMER_ID))
                .isInstanceOf(AccountServiceUnavailableException.class)
                .hasMessageContaining("500");
        server.verify();
    }

    @Test
    void getAccount_whenConnectionFails_returnsUnavailableException() {
        server.expect(requestTo("http://account-service/api/v1/accounts/" + ACCOUNT_ID))
                .andRespond(request -> {
                    throw new ResourceAccessException("connection refused");
                });

        assertThatThrownBy(() -> client.getAccount(ACCOUNT_ID, CUSTOMER_ID))
                .isInstanceOf(AccountServiceUnavailableException.class)
                .hasMessageContaining("unreachable");
        server.verify();
    }

    @Test
    void getAccount_whenBodyIsEmpty_throwsIllegalState() {
        server.expect(requestTo("http://account-service/api/v1/accounts/" + ACCOUNT_ID))
                .andRespond(withSuccess());

        assertThatThrownBy(() -> client.getAccount(ACCOUNT_ID, CUSTOMER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Empty response");
        server.verify();
    }
}
