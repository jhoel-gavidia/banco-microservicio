package com.banco.transfer_service.transfer.exception;

import java.util.UUID;

public class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException(UUID id) {
        super("Transfer not found: " + id);
    }
}
