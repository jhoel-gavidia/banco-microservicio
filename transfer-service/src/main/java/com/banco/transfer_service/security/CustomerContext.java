package com.banco.transfer_service.security;

import java.util.UUID;

public record CustomerContext(UUID customerId, boolean admin) {
}
