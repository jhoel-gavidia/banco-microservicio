package com.banco.transfer_service.transfer.receipt.service;

import com.banco.transfer_service.transfer.receipt.config.ReceiptS3Properties;
import com.banco.transfer_service.transfer.receipt.repository.TransferReceiptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

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
            int updated = receiptRepository.renewLease(
                    java.util.List.of(receiptId),
                    leaseOwner,
                    properties.getUpload().getLeaseDuration().toSeconds()
            );
            if (updated == 0) {
                log.debug("Receipt lease heartbeat did not renew receiptId={} leaseOwner={}", receiptId, leaseOwner);
            }
        } catch (RuntimeException ex) {
            log.warn("Receipt lease heartbeat failed receiptId={} leaseOwner={}", receiptId, leaseOwner, ex);
        }
    }
}
