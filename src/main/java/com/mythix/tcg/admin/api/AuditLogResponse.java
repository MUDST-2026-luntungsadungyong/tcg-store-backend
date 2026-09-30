package com.mythix.tcg.admin.api;

import com.mythix.tcg.admin.domain.AuditLog;
import java.time.LocalDateTime;

public record AuditLogResponse(
        String id,
        String action,
        String targetType,
        String targetId,
        String actorId,
        String actorName,
        String actorEmail,
        String actorRole,
        String actorSellerStatus,
        String details,
        LocalDateTime createdAt) {

    public static AuditLogResponse from(AuditLog log) {
        String actorId = null;
        String actorName = "System";
        String actorEmail = null;
        String actorRole = "SYSTEM";
        String actorSellerStatus = null;

        if (log.getActor() != null) {
            actorId = String.valueOf(log.getActor().getId());
            if (log.getActor().getFullName() != null && !log.getActor().getFullName().isBlank()) {
                actorName = log.getActor().getFullName();
            } else if (log.getActor().getEmail() != null) {
                actorName = log.getActor().getEmail();
            }
            actorEmail = log.getActor().getEmail();
            actorRole = log.getActor().getRole() != null && !log.getActor().getRole().isBlank()
                    ? log.getActor().getRole()
                    : "ADMIN";
            actorSellerStatus = log.getActor().getSellerStatus();
        }

        return new AuditLogResponse(
                String.valueOf(log.getId()),
                log.getAction(),
                log.getTargetType(),
                log.getTargetId(),
                actorId,
                actorName,
                actorEmail,
                actorRole,
                actorSellerStatus,
                log.getDetails(),
                log.getCreatedAt());
    }
}
