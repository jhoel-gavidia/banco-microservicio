package com.banco.transfer_service.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountInfo(
        UUID id,
        String number,
        String currency,
        BigDecimal balance,
        String status
) {
}
