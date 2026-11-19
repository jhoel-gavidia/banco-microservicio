package com.banco.transfer_service.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountMovementInfo(
        UUID id,
        UUID accountId,
        String type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String reference
) {
}
