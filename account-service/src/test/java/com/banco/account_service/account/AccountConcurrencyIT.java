package com.banco.account_service.account;

import com.banco.account_service.account.dto.AccountResponse;
import com.banco.account_service.account.exception.AccountAlreadyExistsException;
import com.banco.account_service.account.service.AccountService;
import com.banco.account_service.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Sql(scripts = "/clean-db.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AccountConcurrencyIT extends PostgresIntegrationTest {

    @Autowired
    private AccountService accountService;

    private static final int THREADS = 8;
    private static final String SAME_NUMBER = "CONCURRENT-001";

    @Test
    void concurrentOpenAccount_sameNumber_exactlyOneWins() throws Exception {
        UUID customerId = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);

        List<Future<Object>> results = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            results.add(executor.submit(() -> {
                start.await(); // todos esperan aqui
                try {
                    return accountService.openAccount(customerId, SAME_NUMBER, "EUR");
                } catch (AccountAlreadyExistsException ex) {
                    return ex; // perdedor esperado
                }
            }));
        }

        start.countDown(); // liberamos a todos al mismo tiempo
        executor.shutdown();

        int winners = 0;
        int losers = 0;
        for (Future<Object> future : results) {
            Object result = future.get();
            if (result instanceof AccountResponse) {
                winners++;
            } else if (result instanceof AccountAlreadyExistsException) {
                losers++;
            }
        }

        assertThat(winners).as("solo un hilo debe crear la cuenta").isEqualTo(1);
        assertThat(losers).as("los demas deben recibir AccountAlreadyExists")
                .isEqualTo(THREADS - 1);
    }
}
