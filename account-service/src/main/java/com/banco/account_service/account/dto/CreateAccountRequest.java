package com.banco.account_service.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(

        @NotBlank(message = "number is required")
        @Size(max = 34, message = "number must be at most 34 characters")
        String number,

        @NotBlank(message = "currency is required")
        String currency

) {
}
