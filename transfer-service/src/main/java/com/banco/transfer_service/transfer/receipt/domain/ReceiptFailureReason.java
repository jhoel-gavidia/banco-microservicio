package com.banco.transfer_service.transfer.receipt.domain;

public enum ReceiptFailureReason {
    UPLOAD_ERROR,
    CHECKSUM_MISMATCH,
    MISSING_OBJECT,
    UNEXPECTED_ERROR
}
