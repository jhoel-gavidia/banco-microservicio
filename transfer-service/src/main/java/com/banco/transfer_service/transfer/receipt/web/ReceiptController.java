package com.banco.transfer_service.transfer.receipt.web;

import com.banco.transfer_service.security.AuthenticatedCustomer;
import com.banco.transfer_service.transfer.receipt.domain.TransferReceipt;
import com.banco.transfer_service.transfer.receipt.dto.ReceiptResponse;
import com.banco.transfer_service.transfer.receipt.service.ReceiptFile;
import com.banco.transfer_service.transfer.receipt.service.ReceiptQueryService;
import com.banco.transfer_service.transfer.receipt.service.ReceiptStorageService;
import com.banco.transfer_service.transfer.receipt.service.ReceiptUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers/{transferId}/receipts")
@RequiredArgsConstructor
public class ReceiptController {

    private final ReceiptUploadService receiptUploadService;
    private final ReceiptQueryService receiptQueryService;
    private final ReceiptStorageService receiptStorageService;
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

    @GetMapping
    public List<ReceiptResponse> list(@PathVariable UUID transferId) {
        return receiptQueryService.list(transferId, authenticatedCustomer.current());
    }

    @GetMapping("/{receiptId}/file")
    public ResponseEntity<InputStreamResource> download(
            @PathVariable UUID transferId,
            @PathVariable UUID receiptId
    ) {
        TransferReceipt receipt = receiptQueryService.getActive(
                transferId, receiptId, authenticatedCustomer.current());
        ReceiptFile file = receiptStorageService.download(receipt);
        try {
            InputStreamResource resource = new InputStreamResource(file.stream()) {
                @Override
                public long contentLength() {
                    return file.sizeBytes();
                }

                @Override
                public String getFilename() {
                    return file.filename();
                }
            };

            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(file.filename(), StandardCharsets.UTF_8)
                    .build();

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(file.contentType()))
                    .contentLength(file.sizeBytes())
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Content-Type-Options", "nosniff")
                    .body(resource);
        } catch (RuntimeException ex) {
            file.close();
            throw ex;
        }
    }

    public static URI receiptLocation(UUID transferId, UUID receiptId) {
        return URI.create("/api/v1/transfers/" + transferId + "/receipts/" + receiptId);
    }
}
