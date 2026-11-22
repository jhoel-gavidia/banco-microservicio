package com.banco.transfer_service.transfer.receipt.web;

import com.banco.transfer_service.security.AuthenticatedCustomer;
import com.banco.transfer_service.transfer.receipt.dto.ReceiptResponse;
import com.banco.transfer_service.transfer.receipt.service.ReceiptUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers/{transferId}/receipts")
@RequiredArgsConstructor
public class ReceiptController {

    private final ReceiptUploadService receiptUploadService;
    private final AuthenticatedCustomer authenticatedCustomer;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReceiptResponse> upload(
            @PathVariable UUID transferId,
            @RequestPart("file") MultipartFile file
    ) {
        ReceiptResponse response = receiptUploadService.upload(
                transferId, file, authenticatedCustomer.current()
        );
        return ResponseEntity
                .created(receiptLocation(transferId, response.id()))
                .body(response);
    }

    public static URI receiptLocation(UUID transferId, UUID receiptId) {
        return URI.create("/api/v1/transfers/" + transferId + "/receipts/" + receiptId);
    }
}
