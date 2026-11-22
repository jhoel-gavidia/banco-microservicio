package com.banco.transfer_service.transfer.receipt.exception;

public class ReceiptStorageUnavailableException extends RuntimeException {

    private final String receiptId;

    public ReceiptStorageUnavailableException(String receiptId, String message, Throwable cause) {
        super(message, cause);
        this.receiptId = receiptId;
    }

    public String getReceiptId() {
        return receiptId;
    }
}
