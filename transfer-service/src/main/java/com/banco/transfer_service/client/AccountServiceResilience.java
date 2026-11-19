package com.banco.transfer_service.client;

import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Component
public class AccountServiceResilience {

    private static final String NAME = "accountService";

    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final ThreadPoolBulkhead bulkhead;
    private final TimeLimiter timeLimiter;

    public AccountServiceResilience(
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            ThreadPoolBulkheadRegistry bulkheadRegistry,
            TimeLimiterRegistry timeLimiterRegistry
    ) {
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(NAME);
        this.retry = retryRegistry.retry(NAME);
        this.bulkhead = bulkheadRegistry.bulkhead(NAME);
        this.timeLimiter = timeLimiterRegistry.timeLimiter(NAME);
    }

    public <T> T call(Supplier<T> supplier) {
        Map<String, String> callerContext = MDC.getCopyOfContextMap();
        Supplier<T> remote = () -> {
            if (callerContext == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(callerContext);
            }
            try {
                return supplier.get();
            } finally {
                MDC.clear();
            }
        };
        Supplier<T> isolated = () -> {
            try {
                return timeLimiter.<T, CompletableFuture<T>>executeFutureSupplier(
                        () -> bulkhead.submit((Callable<T>) remote::get).toCompletableFuture()
                );
            } catch (Exception ex) {
                throw propagate(unwrap(ex));
            }
        };

        Supplier<T> retried = retry.decorateSupplier(isolated);

        return circuitBreaker.decorateSupplier(retried).get();
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static RuntimeException propagate(Throwable cause) {
        if (cause instanceof Error error) {
            throw error;
        }
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof TimeoutException || cause instanceof CancellationException) {
            return new AccountServiceUnavailableException("account-service call timed out", cause);
        }
        return new AccountServiceUnavailableException("account-service call failed", cause);
    }
}
