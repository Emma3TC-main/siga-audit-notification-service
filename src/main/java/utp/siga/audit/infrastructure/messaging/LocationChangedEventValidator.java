package utp.siga.audit.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import utp.siga.audit.application.command.RecordLocationAuditCommand;

@Component
public class LocationChangedEventValidator {
    private static final Set<String> ENVELOPE = Set.of("eventId", "eventType", "schemaVersion",
            "aggregateType", "aggregateId", "occurredAt", "correlationId", "producer", "payload");
    private static final Set<String> PAYLOAD = Set.of("changeType", "actorUserId", "before", "after");
    private static final Set<String> SNAPSHOT = Set.of("id", "code", "name", "locationType",
            "parentId", "active", "version");
    private static final Set<String> TYPES = Set.of("ALMACEN", "PATIO", "ZONA", "PASILLO", "RACK",
            "NIVEL", "POSICION", "TALLER", "RECEPCION", "CUARENTENA", "DESPACHO");
    private static final Map<String, String> ACTIONS = Map.of(
            "CREATED", "LOCATION_CREATED", "UPDATED", "LOCATION_UPDATED",
            "DEACTIVATED", "LOCATION_DEACTIVATED", "REACTIVATED", "LOCATION_REACTIVATED");

    public RecordLocationAuditCommand validateAndExtract(String routingKey, JsonNode envelope) {
        if (!"inventory.location.changed.v1".equals(routingKey)) fail("routing key");
        exact(envelope, ENVELOPE, "envelope");
        textEquals(envelope, "eventType", "LocationChanged");
        if (!envelope.path("schemaVersion").isInt() || envelope.path("schemaVersion").asInt() != 1) fail("schemaVersion");
        textEquals(envelope, "aggregateType", "Location");
        textEquals(envelope, "producer", "inventory-service");
        UUID aggregateId = uuid(envelope, "aggregateId");
        UUID correlationId = uuid(envelope, "correlationId");
        Instant occurredAt = instant(envelope, "occurredAt");
        uuid(envelope, "eventId");

        JsonNode payload = envelope.path("payload");
        exact(payload, PAYLOAD, "payload");
        String changeType = text(payload, "changeType");
        String action = ACTIONS.get(changeType);
        if (action == null) fail("changeType");
        UUID actor = uuid(payload, "actorUserId");
        JsonNode before = payload.get("before");
        if ("CREATED".equals(changeType)) {
            if (before == null || !before.isNull()) fail("created before");
        } else if (before == null || before.isNull()) fail("before");
        if (before != null && !before.isNull()) snapshot(before);
        Map<String, Object> after = snapshot(payload.path("after"));
        if (!aggregateId.toString().equals(after.get("id"))) fail("aggregateId");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("changeType", changeType);
        details.put("before", before == null || before.isNull() ? null : snapshot(before));
        details.put("after", after);
        return new RecordLocationAuditCommand(actor, aggregateId, correlationId, occurredAt, action, details);
    }

    private Map<String, Object> snapshot(JsonNode node) {
        exact(node, SNAPSHOT, "location snapshot");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", uuid(node, "id").toString());
        value.put("code", boundedText(node, "code", 80));
        value.put("name", boundedText(node, "name", 160));
        String type = text(node, "locationType");
        if (!TYPES.contains(type)) fail("locationType");
        value.put("locationType", type);
        JsonNode parent = node.get("parentId");
        value.put("parentId", parent.isNull() ? null : UUID.fromString(parent.asText()).toString());
        if (!node.path("active").isBoolean()) fail("active");
        value.put("active", node.path("active").asBoolean());
        if (!node.path("version").canConvertToLong() || node.path("version").asLong() < 0) fail("version");
        value.put("version", node.path("version").asLong());
        return value;
    }

    private void exact(JsonNode node, Set<String> expected, String field) {
        if (node == null || !node.isObject()) fail(field);
        java.util.HashSet<String> actual = new java.util.HashSet<>();
        Iterator<String> names = node.fieldNames();
        names.forEachRemaining(actual::add);
        if (!actual.equals(expected)) fail(field + " fields");
    }
    private String boundedText(JsonNode node, String field, int max) {
        String value = text(node, field);
        if (value.length() > max) fail(field);
        return value;
    }
    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) fail(field);
        return value.asText();
    }
    private void textEquals(JsonNode node, String field, String expected) {
        if (!expected.equals(text(node, field))) fail(field);
    }
    private UUID uuid(JsonNode node, String field) {
        try { return UUID.fromString(text(node, field)); } catch (RuntimeException ex) { throw bad(field, ex); }
    }
    private Instant instant(JsonNode node, String field) {
        try { return Instant.parse(text(node, field)); } catch (RuntimeException ex) { throw bad(field, ex); }
    }
    private void fail(String field) { throw new IllegalArgumentException("Invalid LocationChanged " + field); }
    private IllegalArgumentException bad(String field, Exception cause) {
        return new IllegalArgumentException("Invalid LocationChanged " + field, cause);
    }
}
