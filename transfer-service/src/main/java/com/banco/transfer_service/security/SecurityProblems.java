package com.banco.transfer_service.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

public final class SecurityProblems {

    public static final String UNAUTHORIZED_TYPE =
            "https://banco.local/problems/unauthorized";
    public static final String FORBIDDEN_TYPE =
            "https://banco.local/problems/forbidden";

    private SecurityProblems() {
    }

    public static ProblemDetail unauthorized(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, detail
        );
        problem.setType(URI.create(UNAUTHORIZED_TYPE));
        problem.setTitle("Unauthorized");
        return problem;
    }

    public static ProblemDetail forbidden(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, detail
        );
        problem.setType(URI.create(FORBIDDEN_TYPE));
        problem.setTitle("Forbidden");
        return problem;
    }
}
