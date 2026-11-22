package com.banco.transfer_service.transfer.receipt.service;

import java.io.InputStream;

public record ReceiptFile(
        InputStream stream,
        String contentType,
        String filename,
        long sizeBytes,
        Runnable cleanup
) implements AutoCloseable {

    @Override
    public void close() {
        try {
            stream.close();
        } catch (Exception ignored) {
            // best effort close
        } finally {
            if (cleanup != null) {
                cleanup.run();
            }
        }
    }
}
