package com.banco.transfer_service.transfer.domain;

public enum TransferStatus {
    PENDING,
    DEBITED,
    COMPLETING,
    COMPENSATING,
    COMPLETED,
    COMPENSATED,
    FAILED
}
