package utp.siga.audit.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import utp.siga.audit.AuditIntegrationEnvironment;
import utp.siga.audit.application.usecase.RecordLocationAuditService;

@SpringBootTest(properties = {
        "audit.location-consumer.enabled=true",
        "audit.location-consumer.queue=audit.location.changed.v1",
        "audit.location-consumer.routing-key=inventory.location.changed.v1"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LocationChangedConsumerIntegrationTest extends AuditIntegrationEnvironment {
    private static final String QUEUE = "audit.location.changed.v1";
    private static final String ROUTING = "inventory.location.changed.v1";
    @Autowired RabbitTemplate rabbit;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private UUID eventId;
    private UUID locationId;

    @BeforeEach
    void drain() {
        while (rabbit.receive(QUEUE, 50) != null) { }
        while (rabbit.receive(QUEUE + ".dlq", 50) != null) { }
    }

    @AfterEach
    void cleanOwned() {
        if (locationId != null) jdbc.update("DELETE FROM audit.audit_event WHERE aggregate_id=?", locationId);
        if (eventId != null) jdbc.update("DELETE FROM audit.processed_event WHERE event_id=?", eventId);
    }

    @Test
    void realRabbitDeliveryPersistsExactlyOneAuditRecordAndNoNotification() throws Exception {
        eventId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        ObjectNode envelope = event(eventId, locationId, actor, correlation, "CREATED");

        publish(ROUTING, envelope);
        await("SELECT count(*) FROM audit.processed_event WHERE event_id=? AND consumer=?", 1L,
                eventId, RecordLocationAuditService.CONSUMER);
        publish(ROUTING, envelope);
        Thread.sleep(300);

        assertThat(count("SELECT count(*) FROM audit.processed_event WHERE event_id=? AND consumer=?",
                eventId, RecordLocationAuditService.CONSUMER)).isOne();
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE aggregate_id=?", locationId)).isOne();
        var row = jdbc.queryForMap("SELECT event_type,actor_id,aggregate_type,action,result,correlation_id,details "
                + "FROM audit.audit_event WHERE aggregate_id=?", locationId);
        assertThat(row.get("event_type")).isEqualTo("LocationChanged");
        assertThat(row.get("actor_id")).isEqualTo(actor);
        assertThat(row.get("aggregate_type")).isEqualTo("Location");
        assertThat(row.get("action")).isEqualTo("LOCATION_CREATED");
        assertThat(row.get("result")).isEqualTo("SUCCESS");
        assertThat(row.get("correlation_id")).isEqualTo(correlation);
        assertThat(count("SELECT count(*) FROM audit.notification WHERE reference_id=?", locationId)).isZero();
    }

    @Test
    void malformedEventIsDeadLetteredWithoutPersistence() throws Exception {
        eventId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        ObjectNode envelope = event(eventId, locationId, UUID.randomUUID(), UUID.randomUUID(), "UPDATED");
        ((ObjectNode) envelope.path("payload").path("after")).put("unexpected", "rejected");
        publish(ROUTING, envelope);
        Message dead = null;
        long deadline = System.currentTimeMillis() + 5000;
        while (dead == null && System.currentTimeMillis() < deadline) dead = rabbit.receive(QUEUE + ".dlq", 100);
        assertThat(dead).isNotNull();
        assertThat(count("SELECT count(*) FROM audit.processed_event WHERE event_id=?", eventId)).isZero();
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE aggregate_id=?", locationId)).isZero();
    }

    private ObjectNode event(UUID id, UUID aggregate, UUID actor, UUID correlation, String change) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventId", id.toString());
        envelope.put("eventType", "LocationChanged");
        envelope.put("schemaVersion", 1);
        envelope.put("aggregateType", "Location");
        envelope.put("aggregateId", aggregate.toString());
        envelope.put("occurredAt", Instant.parse("2026-10-27T12:00:00Z").toString());
        envelope.put("correlationId", correlation.toString());
        envelope.put("producer", "inventory-service");
        ObjectNode payload = envelope.putObject("payload");
        payload.put("changeType", change);
        payload.put("actorUserId", actor.toString());
        if ("CREATED".equals(change)) payload.putNull("before"); else payload.set("before", snapshot(aggregate, 0));
        payload.set("after", snapshot(aggregate, "CREATED".equals(change) ? 0 : 1));
        return envelope;
    }

    private ObjectNode snapshot(UUID id, long version) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id.toString());
        node.put("code", "LOC-TEST");
        node.put("name", "Test location");
        node.put("locationType", "ALMACEN");
        node.putNull("parentId");
        node.put("active", true);
        node.put("version", version);
        return node;
    }
    private void publish(String routing, ObjectNode envelope) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbit.send(TEST_EXCHANGE, routing,
                new Message(envelope.toString().getBytes(StandardCharsets.UTF_8), properties));
    }
    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private void await(String sql, long expected, Object... args) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            if (count(sql, args) == expected) return;
            Thread.sleep(50);
        }
        assertThat(count(sql, args)).isEqualTo(expected);
    }
}
