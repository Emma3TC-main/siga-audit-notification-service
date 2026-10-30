package utp.siga.audit.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import utp.siga.audit.application.usecase.RecordAuditEventService;

@SpringBootTest(properties = "audit.consumer.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RabbitConsumerIntegrationTest extends AuditIntegrationEnvironment {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @SpyBean
    private MovementConfirmedEventValidator validator;

    @SpyBean
    private RecordAuditEventService recordService;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM audit.audit_event");
        jdbc.execute("DELETE FROM audit.processed_event");
        // Drain any leftover messages from queues
        while (rabbitTemplate.receive(TEST_QUEUE, 100) != null) { }
        while (rabbitTemplate.receive(TEST_QUEUE + ".dlq", 100) != null) { }
    }

    @Test
    void consumesValidMovementConfirmedEventAndPersistsAuditRecord() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        ObjectNode envelope = createValidEnvelope(eventId, movementId, actorId, correlationId);
        publish(TEST_ROUTING_KEY, envelope);

        awaitCount("SELECT count(*) FROM audit.processed_event WHERE event_id = ? AND consumer = ?", 1L,
                eventId, RecordAuditEventService.MOVEMENT_CONFIRMED_CONSUMER);
        awaitCount("SELECT count(*) FROM audit.audit_event WHERE aggregate_id = ?", 1L, movementId);

        var row = jdbc.queryForMap(
                "SELECT id, event_type, actor_id, aggregate_type, aggregate_id, action, result, correlation_id FROM audit.audit_event WHERE aggregate_id = ?",
                movementId);

        UUID auditId = (UUID) row.get("id");
        assertNotNull(auditId);
        org.junit.jupiter.api.Assertions.assertNotEquals(eventId, auditId,
                "audit_event.id must remain its own generated UUID and NOT reuse eventId");
        assertEquals("MovementConfirmed", row.get("event_type"));
        assertEquals(actorId, row.get("actor_id"));
        assertEquals("Movement", row.get("aggregate_type"));
        assertEquals(movementId, row.get("aggregate_id"));
        assertEquals("MOVEMENT_CONFIRMED", row.get("action"));
        assertEquals("SUCCESS", row.get("result"));
        assertEquals(correlationId, row.get("correlation_id"));

        // Verify queue is empty (ACK after commit)
        Message message = rabbitTemplate.receive(TEST_QUEUE, 200);
        org.junit.jupiter.api.Assertions.assertNull(message, "Message must be acknowledged and removed from queue");
    }

    @Test
    void duplicateDeliveryProducesZeroDuplicateEffect() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        ObjectNode envelope = createValidEnvelope(eventId, movementId, actorId, correlationId);

        // Publish first time
        publish(TEST_ROUTING_KEY, envelope);
        awaitCount("SELECT count(*) FROM audit.processed_event WHERE event_id = ? AND consumer = ?", 1L,
                eventId, RecordAuditEventService.MOVEMENT_CONFIRMED_CONSUMER);
        awaitCount("SELECT count(*) FROM audit.audit_event WHERE aggregate_id = ?", 1L, movementId);

        // Publish duplicate event with same eventId
        publish(TEST_ROUTING_KEY, envelope);

        // Wait to allow consumer to receive and process
        Thread.sleep(500);

        // Exactly one record remains
        Long auditCount = jdbc.queryForObject("SELECT count(*) FROM audit.audit_event WHERE aggregate_id = ?",
                Long.class, movementId);
        Long processedCount = jdbc.queryForObject(
                "SELECT count(*) FROM audit.processed_event WHERE event_id = ? AND consumer = ?",
                Long.class, eventId, RecordAuditEventService.MOVEMENT_CONFIRMED_CONSUMER);

        assertEquals(1L, auditCount, "Duplicate delivery must produce zero duplicate audit effect");
        assertEquals(1L, processedCount, "Duplicate delivery must produce zero duplicate processed effect");
    }

    @Test
    void invalidEventIsRejectedToDeadLetterQueueAndNotSilentlyAcked() throws Exception {
        UUID eventId = UUID.randomUUID();
        ObjectNode invalidEnvelope = objectMapper.createObjectNode();
        invalidEnvelope.put("eventId", eventId.toString());
        invalidEnvelope.put("eventType", "MovementConfirmed");
        invalidEnvelope.put("schemaVersion", 999); // Unsupported schema version
        invalidEnvelope.put("aggregateType", "Movement");
        invalidEnvelope.put("aggregateId", UUID.randomUUID().toString());
        invalidEnvelope.put("occurredAt", Instant.now().toString());
        invalidEnvelope.put("correlationId", UUID.randomUUID().toString());
        invalidEnvelope.put("producer", "inventory-service");
        // Missing payload!

        publish(TEST_ROUTING_KEY, invalidEnvelope);

        // Await message in Dead Letter Queue (.dlq)
        Message dlqMessage = null;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && dlqMessage == null) {
            dlqMessage = rabbitTemplate.receive(TEST_QUEUE + ".dlq", 200);
        }

        assertNotNull(dlqMessage, "Invalid event must be rejected to DLQ rather than silently ACKed");
        verify(validator, times(1)).validateAndExtract(anyString(), any());

        // Verify no audit or processed event was persisted
        Long auditCount = jdbc.queryForObject("SELECT count(*) FROM audit.audit_event", Long.class);
        Long processedCount = jdbc.queryForObject(
                "SELECT count(*) FROM audit.processed_event WHERE event_id = ? AND consumer = ?",
                Long.class, eventId, RecordAuditEventService.MOVEMENT_CONFIRMED_CONSUMER);

        assertEquals(0L, auditCount, "No audit event must be created for invalid message");
        assertEquals(0L, processedCount, "No processed event must be created for invalid message");
    }

    @Test
    void transientFailureRetriesSameEventAndRecoversBeforeAck() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        ObjectNode envelope = createValidEnvelope(
                eventId, movementId, UUID.randomUUID(), UUID.randomUUID());

        doThrow(new IllegalStateException("simulated transient failure"))
                .doCallRealMethod()
                .when(recordService)
                .recordMovementConfirmed(eq(eventId), any());

        publish(TEST_ROUTING_KEY, envelope);

        awaitCount("SELECT count(*) FROM audit.processed_event WHERE event_id = ? AND consumer = ?", 1L,
                eventId, RecordAuditEventService.MOVEMENT_CONFIRMED_CONSUMER);
        awaitCount("SELECT count(*) FROM audit.audit_event WHERE aggregate_id = ?", 1L, movementId);
        verify(recordService, timeout(8000).times(2)).recordMovementConfirmed(eq(eventId), any());

        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM audit.processed_event WHERE event_id = ? AND consumer = ?", Long.class,
                eventId, RecordAuditEventService.MOVEMENT_CONFIRMED_CONSUMER),
                "Recovery must preserve one processed_event effect");
        org.junit.jupiter.api.Assertions.assertNull(rabbitTemplate.receive(TEST_QUEUE, 200),
                "Recovered message must be acknowledged");
        org.junit.jupiter.api.Assertions.assertNull(rabbitTemplate.receive(TEST_QUEUE + ".dlq", 200),
                "Recovered message must not be dead-lettered");
    }

    private void publish(String routingKey, ObjectNode envelope) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        Message message = new Message(envelope.toString().getBytes(StandardCharsets.UTF_8), properties);
        rabbitTemplate.send(TEST_EXCHANGE, routingKey, message);
    }

    private void awaitCount(String sql, Long expected, Object... params) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        Long current = 0L;
        while (System.currentTimeMillis() < deadline) {
            current = jdbc.queryForObject(sql, Long.class, params);
            if (expected.equals(current)) {
                return;
            }
            Thread.sleep(50);
        }
        assertEquals(expected, current, "Timeout waiting for count on query: " + sql);
    }

    private ObjectNode createValidEnvelope(UUID eventId, UUID movementId, UUID actorId, UUID correlationId) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", "MovementConfirmed");
        envelope.put("schemaVersion", 1);
        envelope.put("aggregateType", "Movement");
        envelope.put("aggregateId", movementId.toString());
        envelope.put("occurredAt", "2026-10-14T20:00:00Z");
        envelope.put("correlationId", correlationId.toString());
        envelope.put("producer", "inventory-service");

        ObjectNode payload = envelope.putObject("payload");
        ObjectNode movement = payload.putObject("movement");
        movement.put("id", movementId.toString());
        movement.put("movementCode", "MOV-2026-999");
        movement.put("movementType", "ENTRY");
        movement.put("status", "CONFIRMED");
        movement.put("registeredByUserId", UUID.randomUUID().toString());
        movement.put("confirmedByUserId", actorId.toString());
        movement.put("isSensitive", false);
        movement.put("confirmedAt", "2026-10-14T20:00:00Z");
        movement.put("totalBaseCostPen", new BigDecimal("250.000000"));

        ArrayNode details = payload.putArray("details");
        ObjectNode item = details.addObject();
        item.put("id", UUID.randomUUID().toString());
        item.put("lineNumber", 1);
        item.put("productId", UUID.randomUUID().toString());
        item.put("enteredQuantity", 5.0);
        item.put("unitCode", "NIU");
        item.put("conversionFactor", 1.0);
        item.put("stockQuantity", 5.0);
        item.put("baseUnitCostPen", 50.0);

        ArrayNode stock = payload.putArray("stockBalances");
        ObjectNode balance = stock.addObject();
        balance.put("stockBalanceId", UUID.randomUUID().toString());
        balance.put("productId", UUID.randomUUID().toString());
        balance.put("locationId", UUID.randomUUID().toString());
        balance.put("quantity", 25.0);
        balance.put("avgCostPen", 50.0);
        balance.put("minStock", 2.0);

        return envelope;
    }
}
