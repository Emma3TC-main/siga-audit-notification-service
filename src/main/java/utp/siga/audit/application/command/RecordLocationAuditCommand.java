package utp.siga.audit.application.command;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RecordLocationAuditCommand(UUID actorId, UUID aggregateId, UUID correlationId,
        Instant occurredAt, String action, Map<String, Object> details) { }
