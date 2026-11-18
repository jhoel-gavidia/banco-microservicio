package com.banco.transfer_service.transfer.web;

import com.banco.transfer_service.security.AuthenticatedCustomer;
import com.banco.transfer_service.transfer.dto.CreateTransferRequest;
import com.banco.transfer_service.transfer.dto.TransferResponse;
import com.banco.transfer_service.transfer.service.TransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;
    private final AuthenticatedCustomer authenticatedCustomer;

    @PostMapping
    public ResponseEntity<TransferResponse> create(
            @Valid @RequestBody CreateTransferRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        TransferResponse created = transferService.create(
                request, authenticatedCustomer.current(), idempotencyKey
        );

        return ResponseEntity
                .created(URI.create("/api/v1/transfers/" + created.id()))
                .body(created);
    }
}
