package utp.siga.audit.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import utp.siga.audit.application.command.RecordAuditEventCommand;
import utp.siga.audit.domain.model.AuditEvent;
import utp.siga.audit.domain.port.out.AuditEventRepositoryPort;
import utp.siga.audit.domain.port.out.EventIdempotencyPort;

@ExtendWith(MockitoExtension.class)
class RecordAuditEventServiceTest {

    @Mock
    private AuditEventRepositoryPort auditEventRepository;

    @Mock
    private EventIdempotencyPort eventIdempotency;

    private RecordAuditEventService service;

    @BeforeEach
    void setUp() {
        service = new RecordAuditEventService(auditEventRepository, eventIdempotency);
    }

    @Test
    void successfullyRecordsNewMovementConfirmedEvent() {
        UUID eventId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-10-14T21:00:00Z");
        Map<String, Object> details = Map.of("movementCode", "MOV-001", "totalBaseCostPen", 500.0);

        RecordAuditEventCommand command = new RecordAuditEventCommand(
                actorId, aggregateId, correlationId, occurredAt, details);

        when(eventIdempotency.isProcessed(eventId, RecordAuditEventService.MOVEMENT_CONFIRMED_CONSUMER))
                .thenReturn(false);

        boolean result = service.recordMovementConfirmed(eventId, command);

        assertTrue(result);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(captor.capture());

        AuditEvent saved = captor.getValue();
        assertNotNull(saved.getId());
        assertNotEquals(eventId, saved.getId(), "audit_event.id must remain its own generated UUID and NOT reuse eventId");
        assertEquals("MovementConfirmed", saved.getEventType());
        assertEquals(actorId, saved.getActorId());
        assertEquals("Movement", saved.getAggregateType());
        assertEquals(aggregateId, saved.getAggregateId());
        assertEquals("MOVEMENT_CONFIRMED", saved.getAction());
        assertEquals("SUCCESS", saved.getResult());
        assertEquals(correlationId, saved.getCorrelationId());
        assertEquals(details, saved.getDetails());
        assertEquals(occurredAt, saved.getOccurredAt());

        verify(eventIdempotency).recordProcessed(eventId, RecordAuditEventService.MOVEMENT_CONFIRMED_CONSUMER);
    }

    @Test
    void duplicateEventIdHasZeroDuplicateEffect() {
        UUID eventId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-10-14T21:00:00Z");
        Map<String, Object> details = Map.of("movementCode", "MOV-001");

        RecordAuditEventCommand command = new RecordAuditEventCommand(
                actorId, aggregateId, correlationId, occurredAt, details);

        when(eventIdempotency.isProcessed(eventId, RecordAuditEventService.MOVEMENT_CONFIRMED_CONSUMER))
                .thenReturn(true);

        boolean result = service.recordMovementConfirmed(eventId, command);

        assertFalse(result, "Duplicate event must return false");
        verify(auditEventRepository, never()).save(any());
        verify(eventIdempotency, never()).recordProcessed(any(), any());
    }
}
