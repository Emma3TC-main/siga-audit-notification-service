package utp.siga.audit.application.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEventDto(
        UUID id,
        String eventType,
        UUID actorId,
        String aggregateType,
        UUID aggregateId,
        String action,
        String result,
        UUID correlationId,
        Map<String, Object> details,
        Instant occurredAt) {
}
