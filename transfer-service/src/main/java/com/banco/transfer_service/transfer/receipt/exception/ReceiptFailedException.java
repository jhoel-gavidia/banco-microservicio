package com.banco.transfer_service.transfer.receipt.exception;

import com.banco.transfer_service.transfer.receipt.dto.ReceiptResponse;

public class ReceiptFailedException extends RuntimeException {

    private final ReceiptResponse receipt;

    public ReceiptFailedException(ReceiptResponse receipt) {
        super("Receipt upload failed");
        this.receipt = receipt;
    }

    public ReceiptResponse getReceipt() {
        return receipt;
    }
}
