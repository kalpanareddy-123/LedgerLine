package com.snovasys.ledgerline.exception;

import com.snovasys.ledgerline.dto.ErrorResponse;
import com.snovasys.ledgerline.security.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final com.snovasys.ledgerline.service.AuditService auditService;

    public GlobalExceptionHandler(com.snovasys.ledgerline.service.AuditService auditService) {
        this.auditService = auditService;
    }

    private String getRequestId(HttpServletRequest request) {
        Object reqId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (reqId != null) {
            return reqId.toString();
        }
        return UUID.randomUUID().toString();
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        log.warn("API Exception [{}]: {} - Path: {}", ex.getCode(), ex.getMessage(), request.getRequestURI());

        // Audit the failed state-changing or denied action
        auditService.record(
                auditService.getCurrentActorId(),
                ex.getCode(),
                "API",
                request.getRequestURI(),
                com.snovasys.ledgerline.domain.AuditOutcome.FAILURE,
                auditService.getClientIp(),
                requestId,
                ex.getMessage()
        );

        ErrorResponse error = new ErrorResponse(
                Instant.now().toString(),
                ex.getStatus().value(),
                ex.getCode(),
                ex.getMessage(),
                request.getRequestURI(),
                requestId
        );
        return new ResponseEntity<>(error, ex.getStatus());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));

        ErrorResponse error = new ErrorResponse(
                Instant.now().toString(),
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED",
                details.isBlank() ? "Invalid request content" : details,
                request.getRequestURI(),
                requestId
        );
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(org.springframework.web.servlet.resource.NoResourceFoundException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        ErrorResponse error = new ErrorResponse(
                Instant.now().toString(),
                HttpStatus.NOT_FOUND.value(),
                "RESOURCE_NOT_FOUND",
                "Endpoint not found: " + request.getRequestURI(),
                request.getRequestURI(),
                requestId
        );
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeaderException(MissingRequestHeaderException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        ErrorResponse error = new ErrorResponse(
                Instant.now().toString(),
                HttpStatus.BAD_REQUEST.value(),
                "MISSING_HEADER",
                "Required header missing: " + ex.getHeaderName(),
                request.getRequestURI(),
                requestId
        );
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        ErrorResponse error = new ErrorResponse(
                Instant.now().toString(),
                HttpStatus.UNAUTHORIZED.value(),
                "BAD_CREDENTIALS",
                "Invalid username or password",
                request.getRequestURI(),
                requestId
        );
        return new ResponseEntity<>(error, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ErrorResponse> handleDisabledException(DisabledException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        ErrorResponse error = new ErrorResponse(
                Instant.now().toString(),
                HttpStatus.FORBIDDEN.value(),
                "ACCOUNT_DISABLED",
                "User account is disabled",
                request.getRequestURI(),
                requestId
        );
        return new ResponseEntity<>(error, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);

        auditService.record(
                auditService.getCurrentActorId(),
                "ACCESS_DENIED",
                "API",
                request.getRequestURI(),
                com.snovasys.ledgerline.domain.AuditOutcome.DENIED,
                auditService.getClientIp(),
                requestId,
                ex.getMessage()
        );

        ErrorResponse error = new ErrorResponse(
                Instant.now().toString(),
                HttpStatus.FORBIDDEN.value(),
                "ACCESS_DENIED",
                "You do not have permission to access this resource",
                request.getRequestURI(),
                requestId
        );
        return new ResponseEntity<>(error, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        // Log the stack trace internally on the server, but NEVER leak it to client
        log.error("Unhandled internal server error [RequestID: {}]: ", requestId, ex);

        ErrorResponse error = new ErrorResponse(
                Instant.now().toString(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred. Please contact support.",
                request.getRequestURI(),
                requestId
        );
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
