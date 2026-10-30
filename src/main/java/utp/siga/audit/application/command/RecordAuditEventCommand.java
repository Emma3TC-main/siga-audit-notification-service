package utp.siga.audit.application.command;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RecordAuditEventCommand(
        UUID actorId,
        UUID aggregateId,
        UUID correlationId,
        Instant occurredAt,
        Map<String, Object> details) {
}
