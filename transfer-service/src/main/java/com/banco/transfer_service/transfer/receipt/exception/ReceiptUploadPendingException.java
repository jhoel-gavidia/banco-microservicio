package com.banco.transfer_service.transfer.receipt.exception;

import com.banco.transfer_service.transfer.receipt.dto.ReceiptResponse;

public class ReceiptUploadPendingException extends RuntimeException {

    private final ReceiptResponse receipt;

    public ReceiptUploadPendingException(ReceiptResponse receipt) {
        super("Receipt upload result is pending");
        this.receipt = receipt;
    }

    public ReceiptResponse getReceipt() {
        return receipt;
    }
}
