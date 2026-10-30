package utp.siga.audit.application.usecase;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.audit.application.command.RecordIdentityAuditCommand;
import utp.siga.audit.application.port.in.RecordIdentityAuditUseCase;
import utp.siga.audit.domain.model.AuditEvent;
import utp.siga.audit.domain.port.out.AuditEventRepositoryPort;
import utp.siga.audit.domain.port.out.EventIdempotencyPort;

@Service
public class RecordIdentityAuditService implements RecordIdentityAuditUseCase {
    public static final String CONSUMER = "audit-identity-events-consumer";

    private final AuditEventRepositoryPort events;
    private final EventIdempotencyPort idempotency;

    public RecordIdentityAuditService(AuditEventRepositoryPort events, EventIdempotencyPort idempotency) {
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency must not be null");
    }

    @Override
    @Transactional
    public boolean record(UUID eventId, RecordIdentityAuditCommand command) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        if (!idempotency.claim(eventId, CONSUMER)) {
            return false;
        }
        events.save(new AuditEvent(UUID.randomUUID(), command.eventType(), command.actorId(),
                command.aggregateType(), command.aggregateId(), command.action(), "SUCCESS",
                command.correlationId(), command.details(), command.occurredAt()));
        return true;
    }
}
