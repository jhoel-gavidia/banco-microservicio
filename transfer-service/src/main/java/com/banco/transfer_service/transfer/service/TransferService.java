package com.banco.transfer_service.transfer.service;

import com.banco.transfer_service.security.CustomerContext;
import com.banco.transfer_service.transfer.dto.CreateTransferRequest;
import com.banco.transfer_service.transfer.dto.TransferResponse;

public interface TransferService {

    TransferResponse create(
            CreateTransferRequest request,
            CustomerContext customer,
            String idempotencyKey
    );
}
