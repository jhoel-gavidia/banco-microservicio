package com.banco.account_service.account.web;

import com.banco.account_service.account.dto.AccountResponse;
import com.banco.account_service.account.dto.CreateAccountRequest;
import com.banco.account_service.account.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<AccountResponse> openAccount(
            @Valid @RequestBody CreateAccountRequest request
    ) {
        AccountResponse created = accountService.openAccount(
                request.customerId(),
                request.number(),
                request.currency()
        );

        return ResponseEntity
                .created(URI.create("/api/v1/accounts/" + created.id()))
                .body(created);
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<AccountResponse> getAccountById(
            @PathVariable UUID accountId
    ) {
        return ResponseEntity.ok(accountService.getAccountById(accountId));
    }
}
