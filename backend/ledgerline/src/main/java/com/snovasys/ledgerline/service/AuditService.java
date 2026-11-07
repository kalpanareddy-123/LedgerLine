package com.snovasys.ledgerline.service;

import com.snovasys.ledgerline.domain.AuditLog;
import com.snovasys.ledgerline.domain.AuditOutcome;
import com.snovasys.ledgerline.repository.AuditLogRepository;
import com.snovasys.ledgerline.security.RequestIdFilter;
import com.snovasys.ledgerline.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Writes an audit entry in an isolated transaction so failed attempts are recorded
     * even if the calling transaction rolls back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID actorId, String action, String targetType, String targetId,
                       AuditOutcome outcome, String ip, String requestId, String details) {
        try {
            AuditLog entry = new AuditLog(actorId, action, targetType, targetId, outcome, ip, requestId, details);
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.error("Failed to write audit log entry for action: {}", action, e);
        }
    }

    /**
     * Context-aware helper extracting actor, ip, and requestId from current request/security context.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
