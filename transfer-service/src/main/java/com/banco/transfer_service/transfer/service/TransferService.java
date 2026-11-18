package com.banco.transfer_service.transfer.service;

import com.banco.transfer_service.security.CustomerContext;
import com.banco.transfer_service.transfer.dto.CreateTransferRequest;
import com.banco.transfer_service.transfer.dto.TransferResponse;

import java.util.List;
import java.util.UUID;

public interface TransferService {

    TransferResponse create(
            CreateTransferRequest request,
            CustomerContext customer,
            String idempotencyKey
    );

    TransferResponse getById(UUID id, CustomerContext customer);

    List<TransferResponse> list(CustomerContext customer);
}
