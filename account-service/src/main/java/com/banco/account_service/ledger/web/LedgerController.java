package com.banco.account_service.ledger.web;

import com.banco.account_service.ledger.dto.LedgerEntryResponse;
import com.banco.account_service.ledger.dto.MoneyMovementRequest;
import com.banco.account_service.ledger.service.LedgerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerService ledgerService;

    @PostMapping("/deposits")
    public ResponseEntity<LedgerEntryResponse> deposit(
            @PathVariable UUID accountId,
            @Valid @RequestBody MoneyMovementRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ledgerService.deposit(accountId, request.amount(), request.reference()));
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<LedgerEntryResponse> withdraw(
            @PathVariable UUID accountId,
            @Valid @RequestBody MoneyMovementRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ledgerService.withdraw(accountId, request.amount(), request.reference()));
    }

    @GetMapping("/movements")
    public ResponseEntity<List<LedgerEntryResponse>> getMovements(
            @PathVariable UUID accountId
    ) {
        return ResponseEntity.ok(ledgerService.getMovements(accountId));
    }
}
