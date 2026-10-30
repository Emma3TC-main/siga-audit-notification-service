package utp.siga.audit.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import utp.siga.audit.AuditIntegrationEnvironment;
import utp.siga.audit.application.command.RecordMovementNotificationCommand;
import utp.siga.audit.application.dto.NotificationPageDto;
import utp.siga.audit.application.exception.NotificationNotFoundException;
import utp.siga.audit.application.query.ListNotificationsQuery;
import utp.siga.audit.application.usecase.NotificationService;
import utp.siga.audit.domain.model.Notification;

@SpringBootTest
class PostgresNotificationRepositoryTest extends AuditIntegrationEnvironment {

    @Autowired
    private PostgresNotificationRepository repository;

    @Autowired
    private NotificationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanOwnedFixtures() {
        jdbc.update("DELETE FROM audit.notification WHERE type = ?", NotificationService.TYPE);
        jdbc.update("DELETE FROM audit.processed_event WHERE consumer = ?", NotificationService.CONSUMER);
    }

    @Test
    void insertsListsFiltersCountsAndPaginatesWithStableOrdering() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Instant sameTime = Instant.parse("2026-10-20T12:00:00Z");
        UUID lowerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higherId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        repository.save(notification(lowerId, owner, sameTime, null));
        repository.save(notification(higherId, owner, sameTime, Instant.parse("2026-10-20T13:00:00Z")));
        repository.save(notification(UUID.randomUUID(), other, sameTime.plusSeconds(1), null));

        NotificationPageDto first = service.list(new ListNotificationsQuery(owner, false, 0, 1));
        NotificationPageDto second = service.list(new ListNotificationsQuery(owner, false, 1, 1));
        NotificationPageDto unread = service.list(new ListNotificationsQuery(owner, true, 0, 20));

        assertEquals(2L, first.totalElements());
        assertEquals(higherId, first.content().getFirst().id());
        assertEquals(lowerId, second.content().getFirst().id());
        assertEquals(1L, unread.totalElements());
        assertEquals(lowerId, unread.content().getFirst().id());
        assertEquals("INFO", unread.content().getFirst().severity());
        assertEquals("Movement", unread.content().getFirst().referenceType());
        assertEquals(1L, service.countUnread(owner));
    }

    @Test
    void markReadIsOwnerScopedAndPreservesFirstReadTimestamp() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        repository.save(notification(id, owner, Instant.parse("2026-10-20T12:00:00Z"), null));

        assertThrows(NotificationNotFoundException.class, () -> service.markRead(id, other));
        service.markRead(id, owner);
        Instant firstReadAt = jdbc.queryForObject(
                "SELECT read_at FROM audit.notification WHERE id = ?",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant(),
                id);
        Thread.sleep(5);
        service.markRead(id, owner);
        Instant repeatedReadAt = jdbc.queryForObject(
                "SELECT read_at FROM audit.notification WHERE id = ?",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant(),
                id);

        assertEquals(firstReadAt, repeatedReadAt);
        assertEquals(0L, service.countUnread(owner));
        assertThrows(NotificationNotFoundException.class,
                () -> service.markRead(UUID.randomUUID(), owner));
    }

    @Test
    void failedInsertRollsBackProcessedEventClaim() {
        UUID eventId = UUID.randomUUID();
        RecordMovementNotificationCommand invalid = new RecordMovementNotificationCommand(
                null,
                UUID.randomUUID(),
                "MOV-INVALID",
                "ENTRY",
                Instant.parse("2026-10-20T12:00:00Z"));

        assertThrows(NullPointerException.class, () -> service.recordMovementConfirmed(eventId, invalid));
        assertEquals(0L, count("SELECT count(*) FROM audit.processed_event WHERE event_id = ?", eventId));
        assertEquals(0L, count("SELECT count(*) FROM audit.notification WHERE reference_id = ?", invalid.movementId()));
    }

    @Test
    void concurrentDuplicateEventCreatesExactlyOneNotification() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        RecordMovementNotificationCommand command = new RecordMovementNotificationCommand(
                UUID.randomUUID(), movementId, "MOV-CONCURRENT", "TRANSFER",
                Instant.parse("2026-10-20T12:00:00Z"));
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return service.recordMovementConfirmed(eventId, command);
                }));
            }
            start.countDown();
            int created = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(5, TimeUnit.SECONDS)) {
                    created++;
                }
            }
            assertEquals(1, created);
            assertEquals(1L, count("SELECT count(*) FROM audit.processed_event WHERE event_id = ? AND consumer = ?",
                    eventId, NotificationService.CONSUMER));
            assertEquals(1L, count("SELECT count(*) FROM audit.notification WHERE reference_id = ?", movementId));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void firstEventUsesCanonicalTimestampAndDuplicateHasNoEffect() {
        UUID eventId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-10-20T12:34:56Z");
        RecordMovementNotificationCommand command = new RecordMovementNotificationCommand(
                UUID.randomUUID(), movementId, "MOV-2026-00042", "ENTRY", occurredAt);

        assertTrue(service.recordMovementConfirmed(eventId, command));
        assertFalse(service.recordMovementConfirmed(eventId, command));
        assertEquals(occurredAt, jdbc.queryForObject(
                "SELECT created_at FROM audit.notification WHERE reference_id = ?",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant(), movementId));
        assertEquals(1L, count("SELECT count(*) FROM audit.notification WHERE reference_id = ?", movementId));
    }

    private Notification notification(UUID id, UUID owner, Instant createdAt, Instant readAt) {
        return new Notification(id, owner, NotificationService.TYPE, NotificationService.TITLE,
                "El movimiento MOV-TEST (ENTRY) fue confirmado.", "Movement", UUID.randomUUID(), createdAt, readAt);
    }

    private long count(String sql, Object... parameters) {
        Long value = jdbc.queryForObject(sql, Long.class, parameters);
        return value == null ? 0L : value;
    }
}
