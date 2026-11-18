package com.banco.account_service.idempotency;

import com.banco.account_service.idempotency.exception.IdempotencyKeyInProgressException;
import com.banco.account_service.ledger.dto.LedgerEntryResponse;
import com.banco.account_service.ledger.service.LedgerService;
import com.banco.account_service.security.CustomerContext;
import com.banco.account_service.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Sql(scripts = "/clean-db.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class IdempotencyConcurrencyIT extends PostgresIntegrationTest {

    private static final String REFERENCE = "ref-concurrent-key";
    private static final BigDecimal AMOUNT = BigDecimal.TEN;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${DB_URL}")
    private String url;

    @Value("${DB_USER}")
    private String user;

    @Value("${DB_PASSWORD}")
    private String password;

    @Test
    void begin_whenWinnerIsInProgress_returnsConflict() throws Exception {
        Object result = depositAgainstWinner("IN_PROGRESS", null, null);

        assertThat(result).isInstanceOf(IdempotencyKeyInProgressException.class);
    }

    @Test
    void begin_whenWinnerAlreadyCompleted_replaysStoredResponse() throws Exception {
        UUID cachedId = UUID.randomUUID();
        UUID cachedAccountId = UUID.randomUUID();
        String body = """
                {"id":"%s","accountId":"%s","type":"CREDIT","amount":10,
                 "balanceAfter":10,"reference":"%s","createdAt":"2026-01-01T00:00:00Z"}
                """.formatted(cachedId, cachedAccountId, REFERENCE).replace("\n", "");

        Object result = depositAgainstWinner("COMPLETED", 201, body);

        assertThat(result).isInstanceOf(LedgerEntryResponse.class);
        LedgerEntryResponse replay = (LedgerEntryResponse) result;
        assertThat(replay.id()).isEqualTo(cachedId);
        assertThat(replay.reference()).isEqualTo(REFERENCE);
    }

    private Object depositAgainstWinner(String status, Integer responseStatus, String responseBody)
            throws Exception {
        UUID customerId = UUID.randomUUID();
        String key = "CONCURRENT-KEY-" + UUID.randomUUID();

        UUID accountId = jdbcTemplate.queryForObject(
                "INSERT INTO accounts (customer_id, number, currency) "
                        + "VALUES (?, ?, 'EUR') RETURNING id",
                UUID.class, customerId, "IDEMP-" + UUID.randomUUID().toString().substring(0, 12)
        );
        String hash = hash(accountId, AMOUNT, REFERENCE);

        CountDownLatch blockerInserted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        ExecutorService blockerExecutor = Executors.newSingleThreadExecutor();
        Future<?> blocker = blockerExecutor.submit(() -> {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 PreparedStatement statement = connection.prepareStatement(
                         "INSERT INTO idempotency_keys "
                                 + "(customer_id, account_id, idempotency_key, request_hash, "
                                 + "status, response_status, response_body) "
                                 + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                connection.setAutoCommit(false);
                statement.setObject(1, customerId);
                statement.setObject(2, accountId);
                statement.setString(3, key);
                statement.setString(4, hash);
                statement.setString(5, status);
                if (responseStatus == null) {
                    statement.setNull(6, Types.INTEGER);
                } else {
                    statement.setObject(6, responseStatus);
                }
                if (responseBody == null) {
                    statement.setNull(7, Types.VARCHAR);
                } else {
                    statement.setString(7, responseBody);
                }
                statement.executeUpdate();

                blockerInserted.countDown();
                if (!releaseBlocker.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Blocker was not released");
                }
                connection.commit();
                return null;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        assertThat(blockerInserted.await(20, TimeUnit.SECONDS)).isTrue();

        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        Future<Object> worker = workerExecutor.submit(() -> {
            try {
                return ledgerService.deposit(
                        accountId, AMOUNT, REFERENCE, key, new CustomerContext(customerId, false)
                );
            } catch (RuntimeException ex) {
                return ex;
            }
        });

        awaitBlockedInsert();
        assertThat(worker.isDone()).as("el worker debe estar esperando la fila no confirmada").isFalse();
        releaseBlocker.countDown();

        assertThat(blocker.get(20, TimeUnit.SECONDS)).isNull();
        Object result = worker.get(20, TimeUnit.SECONDS);
        blockerExecutor.shutdownNow();
        workerExecutor.shutdownNow();
        return result;
    }

    private void awaitBlockedInsert() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity "
                            + "WHERE wait_event_type = 'Lock' "
                            + "AND query ILIKE '%idempotency_keys%'",
                    Integer.class
            );
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(100);
        }
    }

    private static String hash(UUID accountId, BigDecimal amount, String reference) {
        String payload = accountId + "|CREDIT|"
                + amount.stripTrailingZeros().toPlainString() + "|" + reference;
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(payload.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}