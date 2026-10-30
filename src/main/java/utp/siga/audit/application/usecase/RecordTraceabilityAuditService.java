package utp.siga.audit.application.usecase;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.audit.application.command.RecordTraceabilityAuditCommand;
import utp.siga.audit.application.port.in.RecordTraceabilityAuditUseCase;
import utp.siga.audit.domain.model.AuditEvent;
import utp.siga.audit.domain.port.out.AuditEventRepositoryPort;
import utp.siga.audit.domain.port.out.EventIdempotencyPort;

@Service
public class RecordTraceabilityAuditService implements RecordTraceabilityAuditUseCase {
    public static final String LOT_CONSUMER = "audit-lot-changed-consumer";
    public static final String ASSET_CONSUMER = "audit-asset-changed-consumer";

    private final AuditEventRepositoryPort events;
    private final EventIdempotencyPort idempotency;

    public RecordTraceabilityAuditService(AuditEventRepositoryPort events, EventIdempotencyPort idempotency) {
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency must not be null");
    }

    @Override
    @Transactional
    public boolean record(UUID eventId, String consumer, RecordTraceabilityAuditCommand command) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(consumer, "consumer must not be null");
        Objects.requireNonNull(command, "command must not be null");
        if (!idempotency.claim(eventId, consumer)) {
            return false;
        }
        events.save(new AuditEvent(UUID.randomUUID(), command.eventType(), command.actorId(),
                command.aggregateType(), command.aggregateId(), command.action(), "SUCCESS",
                command.correlationId(), command.details(), command.occurredAt()));
        return true;
    }
}
