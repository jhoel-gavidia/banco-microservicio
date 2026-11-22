package com.banco.transfer_service.transfer.receipt.service;

import com.banco.transfer_service.security.CustomerContext;
import com.banco.transfer_service.transfer.domain.TransferStatus;
import com.banco.transfer_service.transfer.dto.TransferResponse;
import com.banco.transfer_service.transfer.receipt.domain.ReceiptStatus;
import com.banco.transfer_service.transfer.receipt.domain.TransferReceipt;
import com.banco.transfer_service.transfer.receipt.exception.ReceiptNotFoundException;
import com.banco.transfer_service.transfer.receipt.repository.TransferReceiptRepository;
import com.banco.transfer_service.transfer.service.TransferService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptQueryServiceTest {

    private static final UUID TRANSFER_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final CustomerContext USER = new CustomerContext(CUSTOMER_ID, false);
    private static final CustomerContext ADMIN = new CustomerContext(UUID.randomUUID(), true);

    @Mock
    private TransferService transferService;
    @Mock
    private TransferReceiptRepository receiptRepository;
    @InjectMocks
    private ReceiptQueryService service;

    @Test
    void list_userUsesCustomerFilter() {
        when(transferService.getById(TRANSFER_ID, USER)).thenReturn(transferResponse(CUSTOMER_ID));
        when(receiptRepository.findAllByTransferIdAndCustomerIdOrderByCreatedAtDescIdDesc(TRANSFER_ID, CUSTOMER_ID))
                .thenReturn(List.of(pendingReceipt(CUSTOMER_ID)));

        assertThat(service.list(TRANSFER_ID, USER)).hasSize(1);
    }

    @Test
    void list_adminDoesNotFilterByOwnCustomer() {
        when(transferService.getById(TRANSFER_ID, ADMIN)).thenReturn(transferResponse(CUSTOMER_ID));
        when(receiptRepository.findAllByTransferIdOrderByCreatedAtDescIdDesc(TRANSFER_ID))
                .thenReturn(List.of(pendingReceipt(CUSTOMER_ID)));

        assertThat(service.list(TRANSFER_ID, ADMIN)).hasSize(1);
    }

    @Test
    void getActive_nonActiveReceiptIsNotFound() {
        UUID receiptId = UUID.randomUUID();
        when(transferService.getById(TRANSFER_ID, USER)).thenReturn(transferResponse(CUSTOMER_ID));
        when(receiptRepository.findByIdAndTransferIdAndCustomerId(receiptId, TRANSFER_ID, CUSTOMER_ID))
                .thenReturn(Optional.of(pendingReceipt(CUSTOMER_ID)));

        assertThatThrownBy(() -> service.getActive(TRANSFER_ID, receiptId, USER))
                .isInstanceOf(ReceiptNotFoundException.class);
    }

    private static TransferResponse transferResponse(UUID customerId) {
        return new TransferResponse(TRANSFER_ID, customerId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("10.00"), "USD", TransferStatus.COMPLETED, "ref", Instant.now());
    }

    private static TransferReceipt pendingReceipt(UUID customerId) {
        return TransferReceipt.pending(UUID.randomUUID(), TRANSFER_ID, customerId, "11".repeat(32),
                "transfers/t/r/file.pdf", "application/pdf", "file.pdf", 10, "bucket",
                UUID.randomUUID(), Instant.now().plusSeconds(60));
    }
}
