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

class TraceabilityChangedEventValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LotChangedEventValidator lots = new LotChangedEventValidator();
    private final AssetChangedEventValidator assets = new AssetChangedEventValidator();

    @ParameterizedTest
    @MethodSource("lotMappings")
    void mapsEveryLotChangeAndAllowsSystemActor(String changeType, String action) {
        ObjectNode envelope = lotEvent(changeType, UUID.randomUUID(), null);
        var command = lots.validateAndExtract(LotChangedEventValidator.ROUTING_KEY, envelope);
        assertThat(command.action()).isEqualTo(action);
        assertThat(command.actorId()).isNull();
        assertThat(command.aggregateType()).isEqualTo("Lot");
        assertThat(command.details()).containsKeys("changeType", "before", "after");
    }

    @ParameterizedTest
    @MethodSource("assetMappings")
    void mapsEveryAssetChange(String changeType, String action) {
        UUID actor = UUID.randomUUID();
        ObjectNode envelope = assetEvent(changeType, UUID.randomUUID(), actor);
        var command = assets.validateAndExtract(AssetChangedEventValidator.ROUTING_KEY, envelope);
        assertThat(command.action()).isEqualTo(action);
        assertThat(command.actorId()).isEqualTo(actor);
        assertThat(command.aggregateType()).isEqualTo("Asset");
        assertThat(command.details()).containsKeys("changeType", "before", "after");
    }

    @Test
    void rejectsUnsupportedChangesWrongRoutesAndMalformedSnapshots() {
        ObjectNode lot = lotEvent("CREATED", UUID.randomUUID(), null);
        lot.withObject("/payload").put("changeType", "DELETED");
        assertThatThrownBy(() -> lots.validateAndExtract(LotChangedEventValidator.ROUTING_KEY, lot))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lots.validateAndExtract("inventory.other.v1",
                lotEvent("CREATED", UUID.randomUUID(), null))).isInstanceOf(IllegalArgumentException.class);

        ObjectNode asset = assetEvent("REGISTERED", UUID.randomUUID(), UUID.randomUUID());
        asset.withObject("/payload/after").put("locationId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> assets.validateAndExtract(AssetChangedEventValidator.ROUTING_KEY, asset))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAssetWithoutAuthenticatedActorAndSecretTechnicalFields() {
        ObjectNode noActor = assetEvent("REGISTERED", UUID.randomUUID(), UUID.randomUUID());
        noActor.withObject("/payload").putNull("actorUserId");
        assertThatThrownBy(() -> assets.validateAndExtract(AssetChangedEventValidator.ROUTING_KEY, noActor))
                .isInstanceOf(IllegalArgumentException.class);

        ObjectNode secret = assetEvent("REGISTERED", UUID.randomUUID(), UUID.randomUUID());
        secret.withObject("/payload/after/technicalSnapshot").put("accessToken", "must-not-pass");
        assertThatThrownBy(() -> assets.validateAndExtract(AssetChangedEventValidator.ROUTING_KEY, secret))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ObjectNode lotEvent(String change, UUID id, UUID actor) {
        ObjectNode envelope = envelope("LotChanged", "Lot", id);
        ObjectNode payload = envelope.putObject("payload");
        payload.put("changeType", change);
        if (actor == null) payload.putNull("actorUserId"); else payload.put("actorUserId", actor.toString());
        if ("CREATED".equals(change)) payload.putNull("before"); else payload.set("before", lotSnapshot(id, 0));
        payload.set("after", lotSnapshot(id, "CREATED".equals(change) ? 0 : 1));
        return envelope;
    }

    private ObjectNode lotSnapshot(UUID id, long version) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id.toString());
        node.put("productId", UUID.randomUUID().toString());
        node.put("lotNumber", "LOT-1");
        node.putNull("heatNumber");
        node.put("manufacturedAt", "2026-01-01");
        node.put("receivedAt", "2026-01-02");
        node.put("expiresAt", "2027-01-01");
        node.put("status", "ACTIVE");
        node.put("version", version);
        node.put("createdAt", "2026-01-02T10:00:00Z");
        node.put("updatedAt", "2026-01-02T10:00:00Z");
        return node;
    }

    private ObjectNode assetEvent(String change, UUID id, UUID actor) {
        ObjectNode envelope = envelope("AssetChanged", "Asset", id);
        ObjectNode payload = envelope.putObject("payload");
        payload.put("changeType", change);
        payload.put("actorUserId", actor.toString());
        if ("REGISTERED".equals(change)) payload.putNull("before"); else payload.set("before", assetSnapshot(id, 0));
        payload.set("after", assetSnapshot(id, "REGISTERED".equals(change) ? 0 : 1));
        return envelope;
    }

    private ObjectNode assetSnapshot(UUID id, long version) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id.toString());
        node.put("productId", UUID.randomUUID().toString());
        node.put("assetCode", "ASSET-1");
        node.put("serialNumber", "SERIAL-1");
        node.put("brand", "Demo");
        node.putNull("model");
        node.put("operationalStatus", "OPERATIVO");
        node.putObject("technicalSnapshot").put("powerKw", 10);
        node.put("version", version);
        node.put("createdAt", "2026-01-02T10:00:00Z");
        node.put("updatedAt", "2026-01-02T10:00:00Z");
        return node;
    }

    private ObjectNode envelope(String eventType, String aggregateType, UUID id) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", eventType);
        envelope.put("schemaVersion", 1);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", id.toString());
        envelope.put("occurredAt", "2026-10-29T12:00:00Z");
        envelope.put("correlationId", UUID.randomUUID().toString());
        envelope.put("producer", "inventory-service");
        return envelope;
    }

    private static Stream<Arguments> lotMappings() {
        return Stream.of(Arguments.of("CREATED", "LOT_CREATED"), Arguments.of("UPDATED", "LOT_UPDATED"),
                Arguments.of("STATUS_CHANGED", "LOT_STATUS_CHANGED"));
    }

    private static Stream<Arguments> assetMappings() {
        return Stream.of(Arguments.of("REGISTERED", "ASSET_REGISTERED"), Arguments.of("UPDATED", "ASSET_UPDATED"),
                Arguments.of("STATUS_CHANGED", "ASSET_STATUS_CHANGED"));
    }
}
