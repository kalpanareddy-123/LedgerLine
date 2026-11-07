package com.snovasys.ledgerline.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.snovasys.ledgerline.domain.AuditOutcome;
import com.snovasys.ledgerline.dto.ErrorResponse;
import com.snovasys.ledgerline.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public CustomAccessDeniedHandler(ObjectMapper objectMapper, AuditService auditService) {
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setStatus(HttpStatus.FORBIDDEN.value());

        Object reqId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String requestId = reqId != null ? reqId.toString() : UUID.randomUUID().toString();

        auditService.recordAction(
                "ACCESS_DENIED",
                "ENDPOINT",
                request.getRequestURI(),
                AuditOutcome.DENIED,
                "{\"message\":\"" + accessDeniedException.getMessage() + "\"}"
        );

        ErrorResponse error = new ErrorResponse(
                Instant.now().toString(),
                HttpStatus.FORBIDDEN.value(),
                "ACCESS_DENIED",
                "You do not have permission to access this resource",
                request.getRequestURI(),
                requestId
        );

        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}
