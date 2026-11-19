package com.banco.transfer_service.transfer.web;

import com.banco.transfer_service.client.AccountServiceException;
import com.banco.transfer_service.client.AccountServiceUnavailableException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import com.banco.transfer_service.idempotency.exception.IdempotencyKeyInProgressException;
import com.banco.transfer_service.idempotency.exception.IdempotencyKeyReusedException;
import com.banco.transfer_service.security.SecurityProblems;
import com.banco.transfer_service.transfer.exception.TransferNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request validation failed"
        );
        problem.setType(URI.create("https://banco.local/problems/validation-error"));
        problem.setTitle("Validation error");

        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage())
        );
        problem.setProperty("errors", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Malformed or unreadable request body"
        );
        problem.setType(URI.create("https://banco.local/problems/malformed-body"));
        problem.setTitle("Malformed request body");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String detail = String.format(
                "Invalid value '%s' for parameter '%s'", ex.getValue(), ex.getName()
        );
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, detail
        );
        problem.setType(URI.create("https://banco.local/problems/type-mismatch"));
        problem.setTitle("Invalid parameter");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage()
        );
        problem.setType(URI.create("https://banco.local/problems/invalid-argument"));
        problem.setTitle("Invalid argument");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyKeyReused(IdempotencyKeyReusedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, ex.getMessage()
        );
        problem.setType(URI.create("https://banco.local/problems/idempotency-key-reused"));
        problem.setTitle("Idempotency-Key reused");

        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(IdempotencyKeyInProgressException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyKeyInProgress(
            IdempotencyKeyInProgressException ex
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, ex.getMessage()
        );
        problem.setType(URI.create("https://banco.local/problems/idempotency-key-in-progress"));
        problem.setTitle("Idempotency-Key in progress");

        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(TransferNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleTransferNotFound(TransferNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage()
        );
        problem.setType(URI.create("https://banco.local/problems/transfer-not-found"));
        problem.setTitle("Transfer not found");

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(AccountServiceException.class)
    public ResponseEntity<ProblemDetail> handleAccountService(AccountServiceException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatus().value());
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setType(URI.create("https://banco.local/problems/account-service-error"));
        problem.setTitle("Account service error");
        problem.setProperty("accountId", ex.getAccountId());

        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(AccountServiceUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleAccountServiceUnavailable(
            AccountServiceUnavailableException ex
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage()
        );
        problem.setType(URI.create("https://banco.local/problems/account-service-unavailable"));
        problem.setTitle("Account service unavailable");

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ProblemDetail> handleCircuitOpen(CallNotPermittedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "account-service circuit breaker is open"
        );
        problem.setType(URI.create("https://banco.local/problems/account-service-circuit-open"));
        problem.setTitle("Account service circuit open");

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    @ExceptionHandler(BulkheadFullException.class)
    public ResponseEntity<ProblemDetail> handleBulkheadFull(BulkheadFullException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "account-service has no capacity available"
        );
        problem.setType(URI.create("https://banco.local/problems/account-service-bulkhead-full"));
        problem.setTitle("Account service bulkhead full");

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = SecurityProblems.forbidden(ex.getMessage());

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        ProblemDetail problem = SecurityProblems.unauthorized(ex.getMessage());

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }
}
