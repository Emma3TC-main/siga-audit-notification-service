package utp.siga.audit.application.command;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RecordTraceabilityAuditCommand(
        UUID actorId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        UUID correlationId,
        Instant occurredAt,
        String action,
        Map<String, Object> details) {
}
