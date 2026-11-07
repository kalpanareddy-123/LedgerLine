package com.snovasys.ledgerline.service;

import com.snovasys.ledgerline.domain.AuditOutcome;
import com.snovasys.ledgerline.security.RequestIdFilter;
import com.snovasys.ledgerline.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcTemplate jdbcTemplate;

    public AuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Writes an audit entry directly via JdbcTemplate in an autocommit statement.
     * This ensures audit logging never interferes with or joins rollback-only transactions,
     * never holds connection pool locks, and reliably survives failed operations.
     */
    public void record(UUID actorId, String action, String targetType, String targetId,
                       AuditOutcome outcome, String ip, String requestId, String details) {
        try {
            String sql = """
                    INSERT INTO audit_log (id, actor_id, action, target_type, target_id, outcome, ip, request_id, details, created_at)
                    VALUES (gen_random_uuid(), ?::uuid, ?, ?, ?, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP)
                    """;
            String jsonDetails = (details != null && !details.isBlank()) ? details.trim() : "{}";
            if (!jsonDetails.startsWith("{") && !jsonDetails.startsWith("[")) {
                jsonDetails = "{\"info\":\"" + jsonDetails.replace("\"", "\\\"").replace("\n", " ") + "\"}";
            }
            jdbcTemplate.update(sql, actorId, action, targetType, targetId, outcome.name(), ip, requestId, jsonDetails);
        } catch (Exception e) {
            log.error("Failed to write audit log entry for action: {}", action, e);
        }
    }

    /**
     * Context-aware helper extracting actor, ip, and requestId from current request/security context.
     */
    public void recordAction(String action, String targetType, String targetId, AuditOutcome outcome, String details) {
        UUID actorId = getCurrentActorId();
        String ip = getClientIp();
        String requestId = getCurrentRequestId();

        record(actorId, action, targetType, targetId, outcome, ip, requestId, details);
    }

    public UUID getCurrentActorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            return principal.getId();
        }
        return null;
    }

    public String getCurrentRequestId() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest req = attrs.getRequest();
            Object reqId = req.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
            if (reqId != null) {
                return reqId.toString();
            }
        }
        return UUID.randomUUID().toString();
    }

    public String getClientIp() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest req = attrs.getRequest();
            String xForwardedFor = req.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isBlank()) {
                return xForwardedFor.split(",")[0].trim();
            }
            return req.getRemoteAddr();
        }
        return "127.0.0.1";
    }
}
