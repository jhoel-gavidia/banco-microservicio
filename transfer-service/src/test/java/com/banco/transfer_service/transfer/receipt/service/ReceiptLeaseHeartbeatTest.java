package com.banco.transfer_service.transfer.receipt.service;

import com.banco.transfer_service.transfer.receipt.config.ReceiptS3Properties;
import com.banco.transfer_service.transfer.receipt.repository.TransferReceiptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptLeaseHeartbeatTest {

    @Mock
    private TransferReceiptRepository repository;
    @Mock
    private TaskScheduler taskScheduler;

    private ReceiptS3Properties properties;
    private ReceiptLeaseHeartbeat heartbeat;

    @BeforeEach
    void setUp() {
        properties = new ReceiptS3Properties();
        properties.getUpload().setLeaseDuration(Duration.ofMinutes(2));
        TransactionTemplate tx = new TransactionTemplate();
        tx.setTransactionManager(new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { }
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { }
        });
        heartbeat = new ReceiptLeaseHeartbeat(repository, properties, taskScheduler, tx);
    }

    @Test
    void renew_whenUpdated_logsSuccessPath() {
        UUID receiptId = UUID.randomUUID();
        UUID leaseOwner = UUID.randomUUID();
        when(repository.renewLease(eq(List.of(receiptId)), eq(leaseOwner), eq(120L))).thenReturn(1);

        assertThatCode(() -> heartbeat.renew(receiptId, leaseOwner)).doesNotThrowAnyException();

        verify(repository).renewLease(List.of(receiptId), leaseOwner, 120L);
    }

    @Test
    void renew_whenOwnershipLost_doesNotThrow() {
        UUID receiptId = UUID.randomUUID();
        UUID leaseOwner = UUID.randomUUID();
        when(repository.renewLease(any(), any(), eq(120L))).thenReturn(0);

        assertThatCode(() -> heartbeat.renew(receiptId, leaseOwner)).doesNotThrowAnyException();
    }

    @Test
    void renew_whenInfrastructureFails_doesNotThrow() {
        UUID receiptId = UUID.randomUUID();
        UUID leaseOwner = UUID.randomUUID();
        when(repository.renewLease(any(), any(), eq(120L))).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> heartbeat.renew(receiptId, leaseOwner)).doesNotThrowAnyException();
    }
}
