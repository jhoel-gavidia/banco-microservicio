package com.banco.transfer_service.transfer.receipt.service;

import com.banco.transfer_service.transfer.receipt.config.ReceiptS3Properties;
import com.banco.transfer_service.transfer.receipt.repository.TransferReceiptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReceiptLeaseHeartbeat {

    private final TransferReceiptRepository receiptRepository;
    private final ReceiptS3Properties properties;
    private final TaskScheduler receiptTaskScheduler;
    private final TransactionTemplate transactionTemplate;

    public ScheduledFuture<?> start(UUID receiptId, UUID leaseOwner) {
        Duration interval = properties.getUpload().getHeartbeatInterval();
        return receiptTaskScheduler.scheduleAtFixedRate(
                () -> renew(receiptId, leaseOwner),
                Instant.now().plus(interval),
                interval
        );
    }

    public void stop(ScheduledFuture<?> heartbeat) {
        if (heartbeat != null && !heartbeat.isCancelled()) {
            heartbeat.cancel(false);
        }
    }

    void renew(UUID receiptId, UUID leaseOwner) {
        try {
            Integer updated = transactionTemplate.execute(status -> receiptRepository.renewLease(
                    java.util.List.of(receiptId),
                    leaseOwner,
                    properties.getUpload().getLeaseDuration().toSeconds()
            ));
            int rows = updated == null ? 0 : updated;
            if (rows > 0) {
                log.debug("Receipt lease renewed receiptId={} leaseOwner={} updated={}", receiptId, leaseOwner, rows);
            } else {
                log.warn("Receipt lease not renewed; ownership lost or receipt not pending receiptId={} leaseOwner={}",
                        receiptId, leaseOwner);
            }
        } catch (RuntimeException ex) {
            log.warn("Receipt lease heartbeat infrastructure failure receiptId={} leaseOwner={}",
                    receiptId, leaseOwner, ex);
        }
    }
}
