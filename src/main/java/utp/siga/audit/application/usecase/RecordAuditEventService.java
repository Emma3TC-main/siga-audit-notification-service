package utp.siga.audit.application.usecase;

import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.audit.application.command.RecordAuditEventCommand;
import utp.siga.audit.application.port.in.RecordAuditEventUseCase;
import utp.siga.audit.domain.model.AuditEvent;
import utp.siga.audit.domain.port.out.AuditEventRepositoryPort;
import utp.siga.audit.domain.port.out.EventIdempotencyPort;

@Service
public class RecordAuditEventService implements RecordAuditEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecordAuditEventService.class);

    public static final String MOVEMENT_CONFIRMED_CONSUMER = "audit-movement-confirmed-consumer";
    public static final String EVENT_TYPE = "MovementConfirmed";
    public static final String AGGREGATE_TYPE = "Movement";
    public static final String ACTION = "MOVEMENT_CONFIRMED";
    public static final String RESULT_SUCCESS = "SUCCESS";

    private final AuditEventRepositoryPort auditEventRepository;
    private final EventIdempotencyPort eventIdempotency;

    public RecordAuditEventService(AuditEventRepositoryPort auditEventRepository,
            EventIdempotencyPort eventIdempotency) {
        this.auditEventRepository = Objects.requireNonNull(auditEventRepository, "auditEventRepository must not be null");
        this.eventIdempotency = Objects.requireNonNull(eventIdempotency, "eventIdempotency must not be null");
    }

    @Override
    @Transactional
    public boolean recordMovementConfirmed(UUID eventId, RecordAuditEventCommand command) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(command, "command must not be null");

        // 1. check eventId for idempotency
        if (eventIdempotency.isProcessed(eventId, MOVEMENT_CONFIRMED_CONSUMER)) {
            log.info("Event {} already processed by consumer {}; skipping duplicate", eventId, MOVEMENT_CONFIRMED_CONSUMER);
            return false;
        }

        // 2. insert audit_event with its own generated UUID
        UUID auditEventId = UUID.randomUUID();
        AuditEvent auditEvent = new AuditEvent(
                auditEventId,
                EVENT_TYPE,
                command.actorId(),
                AGGREGATE_TYPE,
                command.aggregateId(),
                ACTION,
                RESULT_SUCCESS,
                command.correlationId(),
                command.details(),
                command.occurredAt());
        auditEventRepository.save(auditEvent);

        // 3. insert processed_event
        eventIdempotency.recordProcessed(eventId, MOVEMENT_CONFIRMED_CONSUMER);

        log.info("Persisted audit event {} for domain event {}", auditEventId, eventId);
        return true;
    }
}
