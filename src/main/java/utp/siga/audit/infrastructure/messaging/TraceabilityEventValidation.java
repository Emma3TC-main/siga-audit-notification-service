package utp.siga.audit.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

final class TraceabilityEventValidation {
    static final Set<String> ENVELOPE_REQUIRED = Set.of("eventId", "eventType", "schemaVersion",
            "aggregateType", "aggregateId", "occurredAt", "correlationId", "producer", "payload");
    static final Set<String> ENVELOPE_ALLOWED = Set.of("eventId", "eventType", "schemaVersion",
            "aggregateType", "aggregateId", "occurredAt", "correlationId", "causationId", "producer", "payload");
    static final Set<String> PAYLOAD = Set.of("changeType", "actorUserId", "before", "after");
    private static final Set<String> FORBIDDEN = Set.of("jwt", "token", "password", "secret", "otp",
            "authorization", "accesstoken", "refreshtoken");

    private TraceabilityEventValidation() {
    }

    static void exact(JsonNode node, Set<String> expected, String field) {
        exact(node, expected, expected, field);
    }

    static void exact(JsonNode node, Set<String> required, Set<String> allowed, String field) {
        if (node == null || !node.isObject()) {
            fail(field);
        }
        Set<String> actual = new HashSet<>();
        Iterator<String> fields = node.fieldNames();
        fields.forEachRemaining(actual::add);
        if (!actual.containsAll(required) || !allowed.containsAll(actual)) {
            fail(field + " fields");
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            fail(field);
        }
        return value.asText();
    }

    static String boundedText(JsonNode node, String field, int max) {
        String value = text(node, field);
        if (value.length() > max) {
            fail(field);
        }
        return value;
    }

    static String nullableText(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return boundedText(node, field, max);
    }

    static String nullableDate(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String date = text(node, field);
        try {
            LocalDate.parse(date);
            return date;
        } catch (RuntimeException exception) {
            throw bad(field, exception);
        }
    }

    static UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(text(node, field));
        } catch (RuntimeException exception) {
            throw bad(field, exception);
        }
    }

    static UUID nullableUuid(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : uuid(node, field);
    }

    static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(text(node, field));
        } catch (RuntimeException exception) {
            throw bad(field, exception);
        }
    }

    static long version(JsonNode node) {
        JsonNode value = node.get("version");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            fail("version");
        }
        return value.longValue();
    }

    static void envelope(JsonNode envelope, String eventType, String aggregateType) {
        exact(envelope, ENVELOPE_REQUIRED, ENVELOPE_ALLOWED, "envelope");
        if (!eventType.equals(text(envelope, "eventType"))) fail("eventType");
        if (!envelope.path("schemaVersion").isIntegralNumber() || envelope.path("schemaVersion").intValue() != 1) {
            fail("schemaVersion");
        }
        if (!aggregateType.equals(text(envelope, "aggregateType"))) fail("aggregateType");
        if (!"inventory-service".equals(text(envelope, "producer"))) fail("producer");
        uuid(envelope, "eventId");
        uuid(envelope, "aggregateId");
        uuid(envelope, "correlationId");
        instant(envelope, "occurredAt");
        if (envelope.has("causationId")) uuid(envelope, "causationId");
        assertNoSecrets(envelope);
    }

    static void assertNoSecrets(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            Iterator<String> fields = node.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (FORBIDDEN.contains(field.toLowerCase())) {
                    fail("forbidden secret field " + field);
                }
                assertNoSecrets(node.get(field));
            }
        } else if (node.isArray()) {
            node.forEach(TraceabilityEventValidation::assertNoSecrets);
        }
    }

    static void fail(String field) {
        throw new IllegalArgumentException("Invalid traceability event " + field);
    }

    private static IllegalArgumentException bad(String field, Exception cause) {
        return new IllegalArgumentException("Invalid traceability event " + field, cause);
    }
}
