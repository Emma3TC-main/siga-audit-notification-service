package utp.siga.audit.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
import utp.siga.audit.application.usecase.RecordTraceabilityAuditService;

@SpringBootTest(properties = {
        "audit.lot-consumer.enabled=true",
        "audit.lot-consumer.queue=audit.lot.changed.v1",
        "audit.lot-consumer.routing-key=inventory.lot.changed.v1",
        "audit.asset-consumer.enabled=true",
        "audit.asset-consumer.queue=audit.asset.changed.v1",
        "audit.asset-consumer.routing-key=inventory.asset.changed.v1"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TraceabilityChangedConsumerIntegrationTest extends AuditIntegrationEnvironment {
    private static final String LOT_QUEUE = "audit.lot.changed.v1";
    private static final String ASSET_QUEUE = "audit.asset.changed.v1";
    @Autowired RabbitTemplate rabbit;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private final List<UUID> eventIds = new ArrayList<>();
    private final List<UUID> aggregateIds = new ArrayList<>();

    @BeforeEach
    void drainOwnedQueues() {
        drain(LOT_QUEUE + ".dlq");
        drain(ASSET_QUEUE + ".dlq");
    }

    @AfterEach
    void cleanOwnedRows() {
        aggregateIds.forEach(id -> jdbc.update("DELETE FROM audit.audit_event WHERE aggregate_id=?", id));
        eventIds.forEach(id -> jdbc.update("DELETE FROM audit.processed_event WHERE event_id=?", id));
        eventIds.clear();
        aggregateIds.clear();
    }

    @Test
    void consumesEveryLotActionWithNullableActorAndDeduplicates() throws Exception {
        for (String change : List.of("CREATED", "UPDATED", "STATUS_CHANGED")) {
            UUID event = ownedEvent();
            UUID lot = ownedAggregate();
            ObjectNode envelope = lotEvent(event, lot, null, change);
            publish(LotChangedEventValidator.ROUTING_KEY, envelope);
            awaitProcessed(event, RecordTraceabilityAuditService.LOT_CONSUMER);
            if ("CREATED".equals(change)) {
                publish(LotChangedEventValidator.ROUTING_KEY, envelope);
            }
        }
        Thread.sleep(250);

        assertThat(actions("Lot")).containsExactlyInAnyOrder("LOT_CREATED", "LOT_UPDATED", "LOT_STATUS_CHANGED");
        assertThat(aggregateIds.stream().mapToLong(id -> count(
                "SELECT count(*) FROM audit.audit_event WHERE aggregate_id=?", id)).sum()).isEqualTo(3);
        assertThat(eventIds.stream().mapToLong(id -> count(
                "SELECT count(*) FROM audit.processed_event WHERE event_id=?", id)).sum()).isEqualTo(3);
        assertThat(aggregateIds.stream().mapToLong(id -> count(
                "SELECT count(*) FROM audit.audit_event WHERE aggregate_type='Lot' "
                        + "AND actor_id IS NOT NULL AND aggregate_id=?", id)).sum()).isZero();
        assertNoNotifications();
        assertThat(rabbit.receive(LOT_QUEUE, 100)).isNull();
    }

    @Test
    void consumesEveryAssetActionAndDeduplicatesWithoutPersistingTechnicalSnapshot() throws Exception {
        UUID actor = UUID.randomUUID();
        for (String change : List.of("REGISTERED", "UPDATED", "STATUS_CHANGED")) {
            UUID event = ownedEvent();
            UUID asset = ownedAggregate();
            ObjectNode envelope = assetEvent(event, asset, actor, change);
            publish(AssetChangedEventValidator.ROUTING_KEY, envelope);
            awaitProcessed(event, RecordTraceabilityAuditService.ASSET_CONSUMER);
            if ("REGISTERED".equals(change)) {
                publish(AssetChangedEventValidator.ROUTING_KEY, envelope);
            }
        }
        Thread.sleep(250);

        assertThat(actions("Asset")).containsExactlyInAnyOrder(
                "ASSET_REGISTERED", "ASSET_UPDATED", "ASSET_STATUS_CHANGED");
        assertThat(aggregateIds.stream().mapToLong(id -> count(
                "SELECT count(*) FROM audit.audit_event WHERE aggregate_type='Asset' AND actor_id=? "
                        + "AND aggregate_id=?", actor, id)).sum()).isEqualTo(3);
        String details = aggregateIds.stream().map(id -> jdbc.queryForObject(
                "SELECT details::text FROM audit.audit_event WHERE aggregate_id=?", String.class, id))
                .reduce("", (left, right) -> left + " " + right);
        assertThat(details).doesNotContain("technicalSnapshot", "powerKw", "token", "password");
        assertNoNotifications();
        assertThat(rabbit.receive(ASSET_QUEUE, 100)).isNull();
    }

    @Test
    void malformedLotIsDeadLetteredWithoutDatabaseEffect() throws Exception {
        UUID event = ownedEvent();
        UUID lot = ownedAggregate();
        ObjectNode envelope = lotEvent(event, lot, null, "CREATED");
        envelope.withObject("/payload/after").put("unexpected", true);
        publish(LotChangedEventValidator.ROUTING_KEY, envelope);
        assertDeadLetter(LOT_QUEUE);
        assertNoEffect(event, lot);
    }

    @Test
    void malformedAssetIsDeadLetteredWithoutDatabaseEffect() throws Exception {
        UUID event = ownedEvent();
        UUID asset = ownedAggregate();
        ObjectNode envelope = assetEvent(event, asset, UUID.randomUUID(), "REGISTERED");
        envelope.withObject("/payload/after").put("operationalStatus", "UNKNOWN");
        publish(AssetChangedEventValidator.ROUTING_KEY, envelope);
        assertDeadLetter(ASSET_QUEUE);
        assertNoEffect(event, asset);
    }

    private ObjectNode lotEvent(UUID event, UUID lot, UUID actor, String change) {
        ObjectNode envelope = envelope(event, "LotChanged", "Lot", lot);
        ObjectNode payload = envelope.putObject("payload");
        payload.put("changeType", change);
        if (actor == null) payload.putNull("actorUserId"); else payload.put("actorUserId", actor.toString());
        if ("CREATED".equals(change)) payload.putNull("before"); else payload.set("before", lotSnapshot(lot, 0));
        payload.set("after", lotSnapshot(lot, "CREATED".equals(change) ? 0 : 1));
        return envelope;
    }

    private ObjectNode lotSnapshot(UUID id, long version) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id.toString());
        node.put("productId", UUID.randomUUID().toString());
        node.put("lotNumber", "LOT-E2E");
        node.putNull("heatNumber");
        node.putNull("manufacturedAt");
        node.put("receivedAt", "2026-10-29");
        node.put("expiresAt", "2027-10-29");
        node.put("status", version == 0 ? "ACTIVE" : "QUARANTINED");
        node.put("version", version);
        node.put("createdAt", "2026-10-29T12:00:00Z");
        node.put("updatedAt", "2026-10-29T12:01:00Z");
        return node;
    }

    private ObjectNode assetEvent(UUID event, UUID asset, UUID actor, String change) {
        ObjectNode envelope = envelope(event, "AssetChanged", "Asset", asset);
        ObjectNode payload = envelope.putObject("payload");
        payload.put("changeType", change);
        payload.put("actorUserId", actor.toString());
        if ("REGISTERED".equals(change)) payload.putNull("before"); else payload.set("before", assetSnapshot(asset, 0));
        payload.set("after", assetSnapshot(asset, "REGISTERED".equals(change) ? 0 : 1));
        return envelope;
    }

    private ObjectNode assetSnapshot(UUID id, long version) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id.toString());
        node.put("productId", UUID.randomUUID().toString());
        node.put("assetCode", "ASSET-E2E");
        node.put("serialNumber", "SERIAL-E2E");
        node.put("brand", "Demo");
        node.put("model", "Model");
        node.put("operationalStatus", version == 0 ? "OPERATIVO" : "EN_MANTENIMIENTO");
        node.putObject("technicalSnapshot").put("powerKw", 10);
        node.put("version", version);
        node.put("createdAt", "2026-10-29T12:00:00Z");
        node.put("updatedAt", "2026-10-29T12:01:00Z");
        return node;
    }

    private ObjectNode envelope(UUID event, String eventType, String aggregateType, UUID aggregate) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventId", event.toString());
        envelope.put("eventType", eventType);
        envelope.put("schemaVersion", 1);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregate.toString());
        envelope.put("occurredAt", "2026-10-29T12:00:00Z");
        envelope.put("correlationId", UUID.randomUUID().toString());
        envelope.put("producer", "inventory-service");
        return envelope;
    }

    private void publish(String routingKey, ObjectNode envelope) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(TEST_EXCHANGE, routingKey,
                new Message(envelope.toString().getBytes(StandardCharsets.UTF_8), properties));
    }

    private void awaitProcessed(UUID eventId, String consumer) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            if (count("SELECT count(*) FROM audit.processed_event WHERE event_id=? AND consumer=?",
                    eventId, consumer) == 1) return;
            Thread.sleep(50);
        }
        assertThat(count("SELECT count(*) FROM audit.processed_event WHERE event_id=? AND consumer=?",
                eventId, consumer)).isOne();
    }

    private void assertDeadLetter(String queue) throws Exception {
        Message dead = null;
        long deadline = System.currentTimeMillis() + 5000;
        while (dead == null && System.currentTimeMillis() < deadline) {
            dead = rabbit.receive(queue + ".dlq", 100);
        }
        assertThat(dead).isNotNull();
    }

    private void assertNoEffect(UUID event, UUID aggregate) {
        assertThat(count("SELECT count(*) FROM audit.processed_event WHERE event_id=?", event)).isZero();
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE aggregate_id=?", aggregate)).isZero();
    }

    private void assertNoNotifications() {
        assertThat(aggregateIds.stream().mapToLong(id -> count(
                "SELECT count(*) FROM audit.notification WHERE reference_id=?", id)).sum()).isZero();
    }

    private List<String> actions(String aggregateType) {
        return aggregateIds.stream().flatMap(id -> jdbc.queryForList(
                "SELECT action FROM audit.audit_event WHERE aggregate_type=? AND aggregate_id=?",
                String.class, aggregateType, id).stream()).toList();
    }

    private UUID ownedEvent() {
        UUID id = UUID.randomUUID();
        eventIds.add(id);
        return id;
    }

    private UUID ownedAggregate() {
        UUID id = UUID.randomUUID();
        aggregateIds.add(id);
        return id;
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private void drain(String queue) {
        while (rabbit.receive(queue, 50) != null) { }
    }
}
