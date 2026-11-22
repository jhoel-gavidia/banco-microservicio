package com.banco.transfer_service.transfer.receipt.service;

import com.banco.transfer_service.transfer.receipt.config.ReceiptS3Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReceiptTempFileSweeper {

    private final ReceiptS3Properties properties;

    @Scheduled(fixedDelayString = "${transfer.receipts.download.temp-ttl:1h}")
    public void sweep() {
        Path receiptsDir = properties.getDownload().getTempDir().toAbsolutePath().normalize().resolve("receipts");
        if (!Files.exists(receiptsDir)) {
            return;
        }
        Instant cutoff = Instant.now().minus(properties.getDownload().getTempTtl());
        try (var paths = Files.list(receiptsDir)) {
            paths.filter(Files::isRegularFile).forEach(path -> deleteIfExpired(path, cutoff));
        } catch (IOException ex) {
            log.warn("Could not sweep receipt temp directory {}", receiptsDir, ex);
        }
    }

    private void deleteIfExpired(Path path, Instant cutoff) {
        try {
            Instant modified = Files.getLastModifiedTime(path).toInstant();
            if (modified.isBefore(cutoff)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ex) {
            log.debug("Could not delete receipt temp file {}", path, ex);
        }
    }
}
