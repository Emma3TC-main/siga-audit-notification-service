package utp.siga.audit.application.command;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RecordIdentityAuditCommand(
        String eventType,
        UUID actorId,
        String aggregateType,
        UUID aggregateId,
        String action,
        UUID correlationId,
        Instant occurredAt,
        Map<String, Object> details) {
}
