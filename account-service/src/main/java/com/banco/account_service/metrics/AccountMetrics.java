package com.banco.account_service.metrics;

import com.banco.account_service.ledger.LedgerEntryType;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class AccountMetrics {

    private final MeterRegistry registry;

    public AccountMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void movement(LedgerEntryType type) {
        registry.counter(
                "account.movements.recorded",
                "type", type.name()
        ).increment();
    }
}
