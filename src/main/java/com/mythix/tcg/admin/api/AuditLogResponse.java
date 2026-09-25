package com.mythix.tcg.admin.api;
import com.mythix.tcg.admin.domain.AuditLog; import java.time.LocalDateTime;
public record AuditLogResponse(String id,String action,String targetType,String targetId,String actorName,String details,LocalDateTime createdAt){ public static AuditLogResponse from(AuditLog log){return new AuditLogResponse(String.valueOf(log.getId()),log.getAction(),log.getTargetType(),log.getTargetId(),log.getActor()==null?"System":log.getActor().getFullName(),log.getDetails(),log.getCreatedAt());}}
