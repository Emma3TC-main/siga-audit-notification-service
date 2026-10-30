package utp.siga.audit.application.usecase;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.audit.application.command.RecordLocationAuditCommand;
import utp.siga.audit.application.port.in.RecordLocationAuditUseCase;
import utp.siga.audit.domain.model.AuditEvent;
import utp.siga.audit.domain.port.out.AuditEventRepositoryPort;
import utp.siga.audit.domain.port.out.EventIdempotencyPort;

@Service
public class RecordLocationAuditService implements RecordLocationAuditUseCase {
    public static final String CONSUMER = "audit-location-changed-consumer";
    private final AuditEventRepositoryPort events;
    private final EventIdempotencyPort idempotency;

    public RecordLocationAuditService(AuditEventRepositoryPort events, EventIdempotencyPort idempotency) {
        this.events = events;
        this.idempotency = idempotency;
    }

    @Override
    @Transactional
    public boolean recordLocationChanged(UUID eventId, RecordLocationAuditCommand command) {
        if (!idempotency.claim(eventId, CONSUMER)) return false;
        events.save(new AuditEvent(UUID.randomUUID(), "LocationChanged", command.actorId(), "Location",
                command.aggregateId(), command.action(), "SUCCESS", command.correlationId(),
                command.details(), command.occurredAt()));
        return true;
    }
}
