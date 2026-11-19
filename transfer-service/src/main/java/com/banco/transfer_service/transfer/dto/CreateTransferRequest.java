package com.banco.transfer_service.transfer.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateTransferRequest(

        @NotNull(message = "sourceAccountId is required")
        UUID sourceAccountId,

        @NotNull(message = "targetAccountId is required")
        UUID targetAccountId,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0001", message = "amount must be greater than zero")
        @Digits(
                integer = 15,
                fraction = 4,
                message = "amount must have at most 15 integer digits and 4 decimals"
        )
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        String currency,

        @NotBlank(message = "reference is required")
        @Size(max = 100, message = "reference must be at most 100 characters")
        String reference

) {
}
