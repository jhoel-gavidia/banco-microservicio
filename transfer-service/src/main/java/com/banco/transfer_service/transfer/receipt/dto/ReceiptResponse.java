package com.banco.transfer_service.transfer.receipt.dto;

import com.banco.transfer_service.transfer.receipt.domain.ReceiptFailureReason;
import com.banco.transfer_service.transfer.receipt.domain.ReceiptStatus;
import com.banco.transfer_service.transfer.receipt.domain.TransferReceipt;

import java.time.Instant;
import java.util.UUID;

public record ReceiptResponse(
        UUID id,
        UUID transferId,
        UUID customerId,
        ReceiptStatus status,
        String contentType,
        String originalFilename,
        long sizeBytes,
        String sha256Hex,
        Instant createdAt,
        Instant confirmedAt,
        ReceiptFailureReason failureReason
) {

    public static ReceiptResponse from(TransferReceipt receipt) {
        return new ReceiptResponse(
                receipt.getId(),
                receipt.getTransferId(),
                receipt.getCustomerId(),
                receipt.getStatus(),
                receipt.getContentType(),
                receipt.getOriginalFilename(),
                receipt.getSizeBytes(),
                receipt.getSha256Hex(),
                receipt.getCreatedAt(),
                receipt.getConfirmedAt(),
                receipt.getFailureReason()
        );
    }
}
