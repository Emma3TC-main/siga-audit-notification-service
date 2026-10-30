package utp.siga.audit.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import utp.siga.audit.AuditIntegrationEnvironment;
import utp.siga.audit.application.command.RecordAuditEventCommand;
import utp.siga.audit.application.dto.AuditEventDto;
import utp.siga.audit.application.dto.AuditPageDto;
import utp.siga.audit.application.query.SearchAuditEventsQuery;
import utp.siga.audit.application.usecase.RecordAuditEventService;
import utp.siga.audit.domain.model.AuditEvent;

@SpringBootTest
class PostgresPersistenceAndIdempotencyTest extends AuditIntegrationEnvironment {

    @Autowired
    private PostgresAuditEventRepository auditRepository;

    @Autowired
    private PostgresEventIdempotency idempotency;

    @Autowired
    private RecordAuditEventService recordService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("DELETE FROM audit.audit_event");
        jdbc.execute("DELETE FROM audit.processed_event");
    }

    @Test
    void insertAndQueryAuditEventWithFiltersAndPagination() {
        UUID actor1 = UUID.randomUUID();
        UUID actor2 = UUID.randomUUID();
        UUID movement1 = UUID.randomUUID();
        UUID movement2 = UUID.randomUUID();
        UUID correlation1 = UUID.randomUUID();

        Instant t1 = Instant.parse("2026-10-14T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-14T11:00:00Z");
        Instant t3 = Instant.parse("2026-10-14T12:00:00Z");

        AuditEvent e1 = new AuditEvent(UUID.randomUUID(), "MovementConfirmed", actor1, "Movement", movement1,
                "MOVEMENT_CONFIRMED", "SUCCESS", correlation1, Map.of("key", "val1"), t1);
        AuditEvent e2 = new AuditEvent(UUID.randomUUID(), "MovementConfirmed", actor1, "Movement", movement2,
                "MOVEMENT_CONFIRMED", "SUCCESS", UUID.randomUUID(), Map.of("key", "val2"), t2);
        AuditEvent e3 = new AuditEvent(UUID.randomUUID(), "MovementConfirmed", actor2, "Movement", movement2,
                "MOVEMENT_CONFIRMED", "SUCCESS", correlation1, Map.of("key", "val3"), t3);

        auditRepository.save(e1);
        auditRepository.save(e2);
        auditRepository.save(e3);

        // Filter by actor1
        AuditPageDto pageActor1 = auditRepository.findByFilters(
                new SearchAuditEventsQuery(actor1, null, null, null, null, null, 0, 10));
        assertEquals(2, pageActor1.totalElements());
        assertEquals(2, pageActor1.content().size());

        // Filter by correlation1
        AuditPageDto pageCorr1 = auditRepository.findByFilters(
                new SearchAuditEventsQuery(null, null, null, correlation1, null, null, 0, 10));
        assertEquals(2, pageCorr1.totalElements());

        // Filter by aggregateId movement2
        AuditPageDto pageMov2 = auditRepository.findByFilters(
                new SearchAuditEventsQuery(null, "Movement", movement2, null, null, null, 0, 10));
        assertEquals(2, pageMov2.totalElements());

        // Filter by date range (t2 to t3)
        AuditPageDto pageDate = auditRepository.findByFilters(
                new SearchAuditEventsQuery(null, null, null, null, t2, t3, 0, 10));
        assertEquals(2, pageDate.totalElements());

        // Pagination test: page 0, size 1 -> total 3, content 1
        AuditPageDto pagePagination = auditRepository.findByFilters(
                new SearchAuditEventsQuery(null, null, null, null, null, null, 0, 1));
        assertEquals(3, pagePagination.totalElements());
        assertEquals(1, pagePagination.content().size());
        assertEquals(t3, pagePagination.content().get(0).occurredAt(), "Expected order by occurredAt DESC");
    }

    @Test
    void paginationUsesIdDescendingAsDeterministicTieBreaker() {
        Instant occurredAt = Instant.parse("2026-10-14T12:00:00Z");
        UUID lowerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higherId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        auditRepository.save(new AuditEvent(lowerId, "MovementConfirmed", UUID.randomUUID(), "Movement",
                UUID.randomUUID(), "MOVEMENT_CONFIRMED", "SUCCESS", UUID.randomUUID(), Map.of(), occurredAt));
        auditRepository.save(new AuditEvent(higherId, "MovementConfirmed", UUID.randomUUID(), "Movement",
                UUID.randomUUID(), "MOVEMENT_CONFIRMED", "SUCCESS", UUID.randomUUID(), Map.of(), occurredAt));

        AuditPageDto firstPage = auditRepository.findByFilters(
                new SearchAuditEventsQuery(null, null, null, null, null, null, 0, 1));
        AuditPageDto secondPage = auditRepository.findByFilters(
                new SearchAuditEventsQuery(null, null, null, null, null, null, 1, 1));

        assertEquals(higherId, firstPage.content().get(0).id());
        assertEquals(lowerId, secondPage.content().get(0).id());
    }

    @Test
    void processedEventLifecycleAndDuplicateKeyDetection() {
        UUID eventId = UUID.randomUUID();
        String consumer = "audit-movement-confirmed-consumer";

        assertFalse(idempotency.isProcessed(eventId, consumer));

        idempotency.recordProcessed(eventId, consumer);

        assertTrue(idempotency.isProcessed(eventId, consumer));

        // Duplicate insertion throws DuplicateKeyException
        assertThrows(DuplicateKeyException.class, () ->
                idempotency.recordProcessed(eventId, consumer));
    }

    @Test
    void transactionRollbackLeavesNoProcessedEventAndNoAuditEvent() {
        UUID eventId = UUID.randomUUID();
        UUID auditId = UUID.randomUUID();
        String consumer = "audit-movement-confirmed-consumer";

        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThrows(RuntimeException.class, () -> tx.execute(status -> {
            auditRepository.save(new AuditEvent(auditId, "MovementConfirmed", UUID.randomUUID(), "Movement",
                    UUID.randomUUID(), "MOVEMENT_CONFIRMED", "SUCCESS", UUID.randomUUID(), Map.of(), Instant.now()));
            idempotency.recordProcessed(eventId, consumer);
            throw new RuntimeException("Forced simulated failure before commit");
        }));

        assertFalse(idempotency.isProcessed(eventId, consumer), "Rollback must leave no processed_event");
        Long countAudit = jdbc.queryForObject("SELECT count(*) FROM audit.audit_event WHERE id = ?", Long.class, auditId);
        assertEquals(0L, countAudit, "Rollback must leave no audit_event");
    }

    @Test
    void concurrentDuplicateAttemptsPersistExactlyOneAuditEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        Map<String, Object> details = Map.of("movementCode", "MOV-CONCURRENT-001");

        RecordAuditEventCommand command = new RecordAuditEventCommand(
                actorId, aggregateId, correlationId, occurredAt, details);

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threads);

        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        startLatch.await();
                        try {
                            return recordService.recordMovementConfirmed(eventId, command);
                        } catch (DataIntegrityViolationException expectedConcurrentDuplicate) {
                            return false;
                        }
                    } finally {
                        finishLatch.countDown();
                    }
                }));
            }

            startLatch.countDown();
            assertTrue(finishLatch.await(5, TimeUnit.SECONDS));

            int committed = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(1, TimeUnit.SECONDS)) {
                    committed++;
                }
            }
            assertEquals(1, committed, "Exactly one concurrent attempt must commit");

            Long auditCount = jdbc.queryForObject("SELECT count(*) FROM audit.audit_event WHERE aggregate_id = ?",
                    Long.class, aggregateId);
            Long processedCount = jdbc.queryForObject("SELECT count(*) FROM audit.processed_event WHERE event_id = ?",
                    Long.class, eventId);

            assertEquals(1L, auditCount, "Concurrent duplicate must produce exactly one audit_event");
            assertEquals(1L, processedCount, "Concurrent duplicate must produce exactly one processed_event");
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS), "Executor must terminate cleanly");
        }
    }
}
