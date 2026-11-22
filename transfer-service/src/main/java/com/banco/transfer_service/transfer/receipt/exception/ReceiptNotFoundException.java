package com.banco.transfer_service.transfer.receipt.exception;

import java.util.UUID;

public class ReceiptNotFoundException extends RuntimeException {

    public ReceiptNotFoundException(UUID receiptId, UUID transferId) {
        super("Receipt " + receiptId + " was not found for transfer " + transferId);
    }
}
