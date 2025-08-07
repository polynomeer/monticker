package com.polynomeer.shared.config.global;

import com.polynomeer.shared.common.error.BaseException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BaseException.class)
    public ProblemDetail handleBaseException(BaseException ex, HttpServletRequest request) {
        log.warn("BaseException details: {}", ex.getMetadata());
        ProblemDetail problem = ProblemDetail.forStatus(ex.getErrorCode().getHttpStatus());
        problem.setTitle("Application Error");
        problem.setDetail(ex.getErrorCode().getMessage());
        problem.setInstance(URI.create(request.getRequestURI()));

        problem.setProperty("code", ex.getErrorCode().getCode());
        problem.setProperty("traceId", MDC.get("traceId"));
        problem.setProperty("details", ex.getMetadata());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("Validation error on URI {}: {}", request.getRequestURI(), details);

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setDetail(details);
        problem.setInstance(URI.create(request.getRequestURI()));

        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnknownException(Exception ex, HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String traceId = MDC.get("traceId");
        log.error("[Unhandled Exception] at {} (traceId={}): {}", requestUri, traceId, ex.getMessage(), ex);

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal Server Error");
        problem.setDetail("예기치 못한 오류가 발생했습니다.");
        problem.setInstance(URI.create(requestUri));

        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }

        return problem;
    }
}
