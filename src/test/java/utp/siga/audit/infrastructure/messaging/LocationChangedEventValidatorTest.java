package utp.siga.audit.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LocationChangedEventValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LocationChangedEventValidator validator = new LocationChangedEventValidator();

    @ParameterizedTest
    @MethodSource("mappings")
    void mapsEveryCanonicalChangeType(String changeType, String action) {
        UUID aggregate = UUID.randomUUID();
        var result = validator.validateAndExtract("inventory.location.changed.v1",
                event(changeType, aggregate));
        assertThat(result.action()).isEqualTo(action);
        assertThat(result.aggregateId()).isEqualTo(aggregate);
        assertThat(result.details()).containsKeys("changeType", "before", "after");
    }

    @Test
    void rejectsUnknownFieldsAndWrongRouting() {
        ObjectNode event = event("CREATED", UUID.randomUUID());
        ((ObjectNode) event.path("payload").path("after")).put("token", "must-not-pass");
        assertThatThrownBy(() -> validator.validateAndExtract("inventory.location.changed.v1", event))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateAndExtract("inventory.other.v1",
                event("CREATED", UUID.randomUUID()))).isInstanceOf(IllegalArgumentException.class);
    }

    private ObjectNode event(String change, UUID locationId) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "LocationChanged");
        envelope.put("schemaVersion", 1);
        envelope.put("aggregateType", "Location");
        envelope.put("aggregateId", locationId.toString());
        envelope.put("occurredAt", "2026-10-27T12:00:00Z");
        envelope.put("correlationId", UUID.randomUUID().toString());
        envelope.put("producer", "inventory-service");
        ObjectNode payload = envelope.putObject("payload");
        payload.put("changeType", change);
        payload.put("actorUserId", UUID.randomUUID().toString());
        if ("CREATED".equals(change)) payload.putNull("before"); else payload.set("before", snapshot(locationId, 0));
        payload.set("after", snapshot(locationId, "CREATED".equals(change) ? 0 : 1));
        return envelope;
    }

    private ObjectNode snapshot(UUID id, long version) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id.toString());
        node.put("code", "LOC-1");
        node.put("name", "Location");
        node.put("locationType", "ALMACEN");
        node.putNull("parentId");
        node.put("active", true);
        node.put("version", version);
        return node;
    }

    private static Stream<Arguments> mappings() {
        return Stream.of(Arguments.of("CREATED", "LOCATION_CREATED"),
                Arguments.of("UPDATED", "LOCATION_UPDATED"),
                Arguments.of("DEACTIVATED", "LOCATION_DEACTIVATED"),
                Arguments.of("REACTIVATED", "LOCATION_REACTIVATED"));
    }
}
