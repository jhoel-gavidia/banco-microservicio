package com.banco.transfer_service.client;

import com.banco.transfer_service.correlation.CorrelationContext;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountServiceResilienceTest {

    @AfterEach
    void tearDown() {
        CorrelationContext.clear();
    }

    @Test
    void call_retriesTransientFailuresThenReturns() {
        AccountServiceResilience resilience = resilience(Duration.ofSeconds(2));
        AtomicInteger attempts = new AtomicInteger();

        String result = resilience.call(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new AccountServiceUnavailableException("account-service is down");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void call_doesNotRetryBusinessFailures() {
        AccountServiceResilience resilience = resilience(Duration.ofSeconds(2));
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> resilience.call(() -> {
            attempts.incrementAndGet();
            throw new AccountServiceException(
                    HttpStatus.UNPROCESSABLE_CONTENT, UUID.randomUUID(), "bad request", "{}"
            );
        })).isInstanceOf(AccountServiceException.class);

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void call_propagatesMdcIntoBulkheadThreadWithoutChangesInCaller() {
        AccountServiceResilience resilience = resilience(Duration.ofSeconds(2));
        CorrelationContext.set("corr-resilience");
        AtomicReference<String> seenInRemoteThread = new AtomicReference<>();

        String result = resilience.call(() -> {
            seenInRemoteThread.set(CorrelationContext.current());
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(seenInRemoteThread.get()).isEqualTo("corr-resilience");
        assertThat(CorrelationContext.current()).isEqualTo("corr-resilience");
    }

    @Test
    void call_whenTimeLimitExpires_throwsUnavailable() {
        AccountServiceResilience resilience = resilience(Duration.ofMillis(50));

        assertThatThrownBy(() -> resilience.call(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return "too late";
        }))
                .isInstanceOf(AccountServiceUnavailableException.class)
                .hasMessageContaining("timed out");
    }

    private AccountServiceResilience resilience(Duration timeout) {
        CircuitBreakerRegistry circuitBreakers = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(100)
                        .minimumNumberOfCalls(100)
                        .build()
        );
        RetryRegistry retries = RetryRegistry.of(
                RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ZERO)
                        .retryExceptions(AccountServiceUnavailableException.class)
                        .ignoreExceptions(AccountServiceException.class)
                        .build()
        );
        ThreadPoolBulkheadRegistry bulkheads = ThreadPoolBulkheadRegistry.of(
                ThreadPoolBulkheadConfig.custom()
                        .coreThreadPoolSize(2)
                        .maxThreadPoolSize(2)
                        .queueCapacity(10)
                        .build()
        );
        TimeLimiterRegistry timeLimiters = TimeLimiterRegistry.of(
                TimeLimiterConfig.custom().timeoutDuration(timeout).build()
        );
        return new AccountServiceResilience(circuitBreakers, retries, bulkheads, timeLimiters);
    }
}
