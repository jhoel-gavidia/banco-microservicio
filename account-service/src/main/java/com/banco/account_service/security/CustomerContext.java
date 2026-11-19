package com.banco.account_service.security;

import java.util.UUID;

public record CustomerContext(UUID customerId, boolean admin, boolean internal) {
}
