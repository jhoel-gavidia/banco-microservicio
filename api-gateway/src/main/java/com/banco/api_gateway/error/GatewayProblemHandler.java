package com.banco.api_gateway.error;

import com.banco.api_gateway.correlation.CorrelationContext;
import com.banco.api_gateway.security.SecurityProblems;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.ErrorResponse;

import java.net.URI;

@RestController
@RestControllerAdvice
public class GatewayProblemHandler implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<ProblemDetail> handleError(HttpServletRequest request) {
        int status = statusFrom(request);
        ProblemDetail problem = problemFor(status);
        problem.setInstance(URI.create(uriFrom(request)));
        problem.setProperty("correlationId", correlationFrom(request));
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleException(
            Exception exception,
            HttpServletRequest request
    ) {
        int status = exception instanceof ErrorResponse errorResponse
                ? errorResponse.getStatusCode().value()
                : 500;
        ProblemDetail problem = problemFor(status);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("correlationId", correlationFrom(request));
        return ResponseEntity.status(status).body(problem);
    }

    private ProblemDetail problemFor(int status) {
        return switch (status) {
            case 400 -> SecurityProblems.badRequest("The gateway could not process this request");
            case 404 -> SecurityProblems.notFound("Resource not found");
            case 502, 503, 504 -> SecurityProblems.upstreamError("Upstream service is unavailable");
            default -> SecurityProblems.internalError("The gateway could not complete this request");
        };
    }

    private int statusFrom(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        return value instanceof Integer status ? status : 500;
    }

    private String uriFrom(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return value instanceof String uri && !uri.isBlank() ? uri : request.getRequestURI();
    }

    private String correlationFrom(HttpServletRequest request) {
        Object attribute = request.getAttribute(CorrelationContext.HEADER);
        if (attribute instanceof String value && !value.isBlank()) {
            return value;
        }
        String current = CorrelationContext.current();
        if (current != null && !current.isBlank()) {
            return current;
        }
        String header = request.getHeader(CorrelationContext.HEADER);
        return header != null && !header.isBlank()
                ? header
                : CorrelationContext.getOrGenerate();
    }
}