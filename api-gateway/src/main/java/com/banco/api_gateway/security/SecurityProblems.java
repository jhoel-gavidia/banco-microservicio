package com.banco.api_gateway.security;

import com.banco.api_gateway.correlation.CorrelationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

public final class SecurityProblems {

    public static final String UNAUTHORIZED_TYPE = "https://banco.local/problems/unauthorized";
    public static final String FORBIDDEN_TYPE = "https://banco.local/problems/forbidden";
    public static final String BAD_REQUEST_TYPE = "https://banco.local/problems/invalid-argument";
    public static final String NOT_FOUND_TYPE = "https://banco.local/problems/not-found";
    public static final String UPSTREAM_ERROR_TYPE = "https://banco.local/problems/upstream-error";
    public static final String INTERNAL_ERROR_TYPE = "https://banco.local/problems/internal-error";

    private SecurityProblems() {
    }

    public static ProblemDetail unauthorized(String detail) {
        return problem(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_TYPE, "Unauthorized", detail);
    }

    public static ProblemDetail forbidden(String detail) {
        return problem(HttpStatus.FORBIDDEN, FORBIDDEN_TYPE, "Forbidden", detail);
    }

    public static ProblemDetail badRequest(String detail) {
        return problem(HttpStatus.BAD_REQUEST, BAD_REQUEST_TYPE, "Bad Request", detail);
    }

    public static ProblemDetail notFound(String detail) {
        return problem(HttpStatus.NOT_FOUND, NOT_FOUND_TYPE, "Not Found", detail);
    }

    public static ProblemDetail upstreamError(String detail) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, UPSTREAM_ERROR_TYPE, "Upstream Error", detail);
    }

    public static ProblemDetail internalError(String detail) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_TYPE, "Internal Server Error", detail);
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(type));
        problem.setTitle(title);
        problem.setProperty("correlationId", CorrelationContext.getOrGenerate());
        return problem;
    }
}