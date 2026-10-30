package utp.siga.audit.application.query;

import java.time.Instant;
import java.util.UUID;

public record SearchAuditEventsQuery(
        UUID actorId,
        String aggregateType,
        UUID aggregateId,
        UUID correlationId,
        Instant from,
        Instant to,
        int page,
        int size) {

    public SearchAuditEventsQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page must be greater than or equal to 0");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
    }
}
