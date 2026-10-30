package utp.siga.audit.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import utp.siga.audit.AuditIntegrationEnvironment;
import utp.siga.audit.application.usecase.NotificationService;

@SpringBootTest(properties = "notifications.consumer.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationConsumerIntegrationTest extends AuditIntegrationEnvironment {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @SpyBean
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit.notification WHERE type = ?", NotificationService.TYPE);
        jdbc.update("DELETE FROM audit.processed_event WHERE consumer = ?", NotificationService.CONSUMER);
        drain(TEST_NOTIFICATION_QUEUE);
        drain(TEST_NOTIFICATION_QUEUE + ".dlq");
        drain(TEST_QUEUE);
    }

    @Test
    void validCanonicalEventCreatesNotificationForRegistrantAndKeepsAuditQueueIndependent() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        ObjectNode envelope = createValidEnvelope(eventId, movementId, recipient);

        publish(envelope);
        awaitCount("SELECT count(*) FROM audit.notification WHERE reference_id = ?", 1L, movementId);

        var row = jdbc.queryForMap("""
                SELECT user_id, type, title, message, reference_type, reference_id, created_at, read_at
                FROM audit.notification WHERE reference_id = ?
                """, movementId);
        assertEquals(recipient, row.get("user_id"));
        assertEquals("MOVEMENT_CONFIRMED", row.get("type"));
        assertEquals("Movimiento confirmado", row.get("title"));
        assertEquals("El movimiento MOV-2026-00999 (ENTRY) fue confirmado.", row.get("message"));
        assertEquals("Movement", row.get("reference_type"));
        assertEquals(movementId, row.get("reference_id"));
        assertNull(row.get("read_at"));
        assertEquals("2026-10-20T20:00:00Z",
                ((java.sql.Timestamp) row.get("created_at")).toInstant().toString());

        assertEquals(1L, count("SELECT count(*) FROM audit.processed_event WHERE event_id = ? AND consumer = ?",
                eventId, NotificationService.CONSUMER));
        assertNull(rabbitTemplate.receive(TEST_NOTIFICATION_QUEUE, 200), "Notification delivery must be ACKed");
        assertNotNull(rabbitTemplate.receive(TEST_QUEUE, 1000),
                "Audit must retain its own independently bound delivery");
    }

    @Test
    void duplicateRedeliveryCreatesOneLogicalNotification() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        ObjectNode envelope = createValidEnvelope(eventId, movementId, UUID.randomUUID());
        publish(envelope);
        awaitCount("SELECT count(*) FROM audit.notification WHERE reference_id = ?", 1L, movementId);
        publish(envelope);
        verify(notificationService, timeout(5000).times(2)).recordMovementConfirmed(eq(eventId), any());

        assertEquals(1L, count("SELECT count(*) FROM audit.notification WHERE reference_id = ?", movementId));
        assertEquals(1L, count("SELECT count(*) FROM audit.processed_event WHERE event_id = ? AND consumer = ?",
                eventId, NotificationService.CONSUMER));
    }

    @Test
    void malformedRecipientIsDeadLetteredWithoutPersistence() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        ObjectNode envelope = createValidEnvelope(eventId, movementId, UUID.randomUUID());
        ((ObjectNode) envelope.path("payload").path("movement")).put("registeredByUserId", "invalid");
        publish(envelope);

        assertNotNull(awaitMessage(TEST_NOTIFICATION_QUEUE + ".dlq"));
        assertEquals(0L, count("SELECT count(*) FROM audit.notification WHERE reference_id = ?", movementId));
        assertEquals(0L, count("SELECT count(*) FROM audit.processed_event WHERE event_id = ? AND consumer = ?",
                eventId, NotificationService.CONSUMER));
    }

    @Test
    void transientFailureRetriesSameEventAndAcknowledgesAfterRecovery() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        ObjectNode envelope = createValidEnvelope(eventId, movementId, UUID.randomUUID());
        doThrow(new IllegalStateException("simulated transient failure"))
                .doCallRealMethod()
                .when(notificationService)
                .recordMovementConfirmed(eq(eventId), any());

        publish(envelope);
        awaitCount("SELECT count(*) FROM audit.notification WHERE reference_id = ?", 1L, movementId);
        verify(notificationService, timeout(8000).times(2)).recordMovementConfirmed(eq(eventId), any());
        assertNull(rabbitTemplate.receive(TEST_NOTIFICATION_QUEUE, 200));
        assertNull(rabbitTemplate.receive(TEST_NOTIFICATION_QUEUE + ".dlq", 200));
    }

    @Test
    void exhaustedRetriesDeadLetterWithoutFalseSuccess() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        doThrow(new IllegalStateException("persistent simulated failure"))
                .when(notificationService)
                .recordMovementConfirmed(eq(eventId), any());

        publish(createValidEnvelope(eventId, movementId, UUID.randomUUID()));
        assertNotNull(awaitMessage(TEST_NOTIFICATION_QUEUE + ".dlq"));
        verify(notificationService, timeout(8000).times(3)).recordMovementConfirmed(eq(eventId), any());
        assertEquals(0L, count("SELECT count(*) FROM audit.notification WHERE reference_id = ?", movementId));
        assertEquals(0L, count("SELECT count(*) FROM audit.processed_event WHERE event_id = ? AND consumer = ?",
                eventId, NotificationService.CONSUMER));
    }

    private void publish(ObjectNode envelope) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbitTemplate.send(TEST_EXCHANGE, TEST_ROUTING_KEY,
                new Message(envelope.toString().getBytes(StandardCharsets.UTF_8), properties));
    }

    private Message awaitMessage(String queue) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        Message message = null;
        while (message == null && System.currentTimeMillis() < deadline) {
            message = rabbitTemplate.receive(queue, 200);
        }
        return message;
    }

    private void drain(String queue) {
        while (rabbitTemplate.receive(queue, 50) != null) {
            // Only test-owned queues in the isolated RabbitMQ container are drained.
        }
    }

    private void awaitCount(String sql, long expected, Object... parameters) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        long current = -1;
        while (System.currentTimeMillis() < deadline) {
            current = count(sql, parameters);
            if (current == expected) {
                return;
            }
            Thread.sleep(50);
        }
        assertEquals(expected, current);
    }

    private long count(String sql, Object... parameters) {
        Long value = jdbc.queryForObject(sql, Long.class, parameters);
        return value == null ? 0L : value;
    }

    private ObjectNode createValidEnvelope(UUID eventId, UUID movementId, UUID recipient) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", "MovementConfirmed");
        envelope.put("schemaVersion", 1);
        envelope.put("aggregateType", "Movement");
        envelope.put("aggregateId", movementId.toString());
        envelope.put("occurredAt", "2026-10-20T20:00:00Z");
        envelope.put("correlationId", UUID.randomUUID().toString());
        envelope.put("producer", "inventory-service");

        ObjectNode payload = envelope.putObject("payload");
        ObjectNode movement = payload.putObject("movement");
        movement.put("id", movementId.toString());
        movement.put("movementCode", "MOV-2026-00999");
        movement.put("movementType", "ENTRY");
        movement.put("status", "CONFIRMED");
        movement.put("registeredByUserId", recipient.toString());
        movement.put("confirmedByUserId", UUID.randomUUID().toString());
        movement.put("isSensitive", false);
        movement.put("confirmedAt", "2026-10-20T20:00:00Z");
        movement.put("totalBaseCostPen", new BigDecimal("250.000000"));

        ArrayNode details = payload.putArray("details");
        ObjectNode detail = details.addObject();
        detail.put("id", UUID.randomUUID().toString());
        detail.put("lineNumber", 1);
        detail.put("productId", UUID.randomUUID().toString());
        detail.put("enteredQuantity", 5);
        detail.put("unitCode", "PZA");
        detail.put("conversionFactor", 1);
        detail.put("stockQuantity", 5);
        detail.put("baseUnitCostPen", 50);

        ArrayNode balances = payload.putArray("stockBalances");
        ObjectNode balance = balances.addObject();
        balance.put("stockBalanceId", UUID.randomUUID().toString());
        balance.put("productId", UUID.randomUUID().toString());
        balance.put("locationId", UUID.randomUUID().toString());
        balance.put("quantity", 5);
        balance.put("avgCostPen", 50);
        balance.put("minStock", 1);
        return envelope;
    }
}
