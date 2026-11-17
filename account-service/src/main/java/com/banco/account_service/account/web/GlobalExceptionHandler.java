package com.banco.account_service.account.web;

import com.banco.account_service.account.exception.AccountAlreadyExistsException;
import com.banco.account_service.account.exception.AccountNotFoundException;
import com.banco.account_service.account.exception.InsufficientFundsException;
import com.banco.account_service.idempotency.exception.IdempotencyKeyInProgressException;
import com.banco.account_service.idempotency.exception.IdempotencyKeyReusedException;
import com.banco.account_service.ledger.exception.DuplicateReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleAccountNotFound(AccountNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage()
        );
        problem.setType(URI.create("https://banco.local/problems/account-not-found"));
        problem.setTitle("Account not found");

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(AccountAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleAccountAlreadyExists(AccountAlreadyExistsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, ex.getMessage()
        );
        problem.setType(URI.create("https://banco.local/problems/account-already-exists"));
        problem.setTitle("Account number already in use");

        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

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

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientFunds(InsufficientFundsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage()
        );
        problem.setType(URI.create("https://banco.local/problems/insufficient-funds"));
        problem.setTitle("Insufficient funds");
        problem.setProperty("accountId", ex.getAccountId());
        problem.setProperty("requested", ex.getRequested());
        problem.setProperty("available", ex.getAvailable());

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(problem);
    }

    @ExceptionHandler(DuplicateReferenceException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateReference(DuplicateReferenceException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, ex.getMessage()
        );
        problem.setType(URI.create("https://banco.local/problems/duplicate-reference"));
        problem.setTitle("Duplicate reference");
        problem.setProperty("accountId", ex.getAccountId());
        problem.setProperty("reference", ex.getReference());
        problem.setProperty("type", ex.getType());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
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
    public ResponseEntity<ProblemDetail> handleIdempotencyKeyInProgress(IdempotencyKeyInProgressException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, ex.getMessage()
        );
        problem.setType(URI.create("https://banco.local/problems/idempotency-key-in-progress"));
        problem.setTitle("Idempotency-Key in progress");

        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
