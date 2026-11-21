package com.banco.api_gateway.correlation;

import org.slf4j.MDC;

import java.util.UUID;

public final class CorrelationContext {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationContext() {
    }

    public static void set(String correlationId) {
        MDC.put(MDC_KEY, correlationId);
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static String getOrGenerate() {
        String current = current();
        return current == null || current.isBlank() ? UUID.randomUUID().toString() : current;
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}