package com.snovasys.ledgerline.dto;

import com.snovasys.ledgerline.domain.AuditLog;
import com.snovasys.ledgerline.domain.AuditOutcome;

import java.time.Instant;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        UUID actorId,
        String action,
        String targetType,
        String targetId,
        AuditOutcome outcome,
        String ip,
        String requestId,
        String details,
        Instant createdAt
) {
    public static AuditLogResponse fromEntity(AuditLog log) {
        return new AuditLogResponse(
                log.getId(),
                log.getActorId(),
                log.getAction(),
                log.getTargetType(),
                log.getTargetId(),
                log.getOutcome(),
                log.getIp(),
                log.getRequestId(),
                log.getDetails(),
                log.getCreatedAt()
        );
    }
}
