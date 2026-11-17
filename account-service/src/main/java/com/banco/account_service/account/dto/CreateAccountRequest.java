package com.banco.account_service.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateAccountRequest(

        @NotNull(message = "customerId is required")
        UUID customerId,

        @NotBlank(message = "number is required")
        @Size(max = 34, message = "number must be at most 34 characters")
        String number,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter uppercase ISO code")
        String currency

) {
}
