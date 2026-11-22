package com.banco.transfer_service.transfer.receipt.service;

import com.banco.transfer_service.security.CustomerContext;
import com.banco.transfer_service.transfer.receipt.domain.ReceiptStatus;
import com.banco.transfer_service.transfer.receipt.domain.TransferReceipt;
import com.banco.transfer_service.transfer.receipt.dto.ReceiptResponse;
import com.banco.transfer_service.transfer.receipt.exception.ReceiptNotFoundException;
import com.banco.transfer_service.transfer.receipt.repository.TransferReceiptRepository;
import com.banco.transfer_service.transfer.service.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReceiptQueryService {

    private final TransferService transferService;
    private final TransferReceiptRepository receiptRepository;

    public List<ReceiptResponse> list(UUID transferId, CustomerContext customer) {
        transferService.getById(transferId, customer);
        List<TransferReceipt> receipts = customer.admin()
                ? receiptRepository.findAllByTransferIdOrderByCreatedAtDescIdDesc(transferId)
                : receiptRepository.findAllByTransferIdAndCustomerIdOrderByCreatedAtDescIdDesc(
                        transferId, customer.customerId());
        return receipts.stream().map(ReceiptResponse::from).toList();
    }

    public TransferReceipt getActive(UUID transferId, UUID receiptId, CustomerContext customer) {
        transferService.getById(transferId, customer);
        TransferReceipt receipt = customer.admin()
                ? receiptRepository.findByIdAndTransferId(receiptId, transferId)
                        .orElseThrow(() -> new ReceiptNotFoundException(receiptId, transferId))
                : receiptRepository.findByIdAndTransferIdAndCustomerId(receiptId, transferId, customer.customerId())
                        .orElseThrow(() -> new ReceiptNotFoundException(receiptId, transferId));
        if (receipt.getStatus() != ReceiptStatus.ACTIVE) {
            throw new ReceiptNotFoundException(receiptId, transferId);
        }
        return receipt;
    }
}
