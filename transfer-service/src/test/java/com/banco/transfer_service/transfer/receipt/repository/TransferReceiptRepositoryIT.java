package com.banco.transfer_service.transfer.receipt.repository;

import com.banco.transfer_service.support.PostgresIntegrationTest;
import com.banco.transfer_service.transfer.receipt.domain.ReceiptStatus;
import com.banco.transfer_service.transfer.receipt.domain.TransferReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TransferReceiptRepositoryIT extends PostgresIntegrationTest {

    @Autowired
    private TransferReceiptRepository receiptRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID transferId;
    private UUID customerId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE transfer_receipts, outbox_events, idempotency_keys, transfers RESTART IDENTITY CASCADE");
        transferId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO transfers (id, customer_id, source_account_id, target_account_id, amount, currency, status,
                                       failure_reason, reference, correlation_id, created_at, updated_at, version)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 10.00, 'USD', 'COMPLETED',
                        NULL, 'ref-it', 'corr-it', now(), now(), 0)
                """,
                transferId.toString(), customerId.toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
    }

    @Test
    void claimPending_returnsClaimedEntitiesAndUpdatesLease() {
        UUID receiptId = insertPendingReceipt(Instant.now().minusSeconds(10), null, 1);
        UUID workerId = UUID.randomUUID();

        List<TransferReceipt> claimed = transactionTemplate.execute(status ->
                receiptRepository.claimPending(workerId, 10, 60, 3));

        assertThat(claimed).hasSize(1);
        TransferReceipt receipt = claimed.get(0);
        assertThat(receipt.getId()).isEqualTo(receiptId);
        assertThat(receipt.getStatus()).isEqualTo(ReceiptStatus.PENDING);
        assertThat(receipt.getLeaseOwner()).isEqualTo(workerId);
        assertThat(receipt.getAttempts()).isEqualTo(2);
        assertThat(receipt.getLeaseExpiresAt()).isNotNull();
        assertThat(receipt.getNextAttemptAt()).isNotNull();

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transfer_receipts WHERE id = ?::uuid AND lease_owner = ?::uuid AND attempts = 2",
                Integer.class, receiptId.toString(), workerId.toString());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void claimPending_skipsLockedRowsAndReturnsDisjointClaims() throws Exception {
        int total = 6;
        for (int i = 0; i < total; i++) {
            insertPendingReceipt(Instant.now().minusSeconds(30 + i), null, 1);
        }

        UUID workerA = UUID.randomUUID();
        UUID workerB = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<List<UUID>> fA = executor.submit(() -> claimWithLatch(workerA, ready, start));
        Future<List<UUID>> fB = executor.submit(() -> claimWithLatch(workerB, ready, start));

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<UUID> idsA = fA.get(15, TimeUnit.SECONDS);
        List<UUID> idsB = fB.get(15, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(idsA).doesNotContainAnyElementsOf(idsB);
        assertThat(idsA.size() + idsB.size()).isEqualTo(total);
    }

    private List<UUID> claimWithLatch(UUID workerId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return transactionTemplate.execute(status ->
                receiptRepository.claimPending(workerId, 10, 60, 3)
                        .stream().map(TransferReceipt::getId).toList());
    }

    private UUID insertPendingReceipt(Instant nextAttemptAt, Instant leaseExpiresAt, int attempts) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO transfer_receipts (
                    id, transfer_id, customer_id, status, sha256_hex, object_key, content_type,
                    original_filename, size_bytes, storage_bucket, storage_version_id, attempts,
                    next_attempt_at, lease_owner, lease_expires_at, last_error, failure_reason,
                    confirmed_at, created_at, updated_at
                ) VALUES (
                    ?::uuid, ?::uuid, ?::uuid, 'PENDING', ?, ?, 'application/pdf',
                    'receipt.pdf', 128, 'bucket', NULL, ?,
                    ?, NULL, ?, NULL, NULL,
                    NULL, now(), now()
                )
                """,
                id.toString(), transferId.toString(), customerId.toString(),
                "11".repeat(32), "transfers/" + transferId + "/receipts/" + id + "/receipt.pdf",
                attempts,
                java.sql.Timestamp.from(nextAttemptAt),
                leaseExpiresAt == null ? null : java.sql.Timestamp.from(leaseExpiresAt));
        return id;
    }
}
