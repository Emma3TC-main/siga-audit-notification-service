package utp.siga.audit.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import utp.siga.audit.application.command.RecordTraceabilityAuditCommand;

@Component
public class AssetChangedEventValidator {
    public static final String ROUTING_KEY = "inventory.asset.changed.v1";
    private static final Set<String> SNAPSHOT = Set.of("id", "productId", "assetCode", "serialNumber", "brand",
            "model", "operationalStatus", "technicalSnapshot", "version", "createdAt", "updatedAt");
    private static final Set<String> STATUSES = Set.of("OPERATIVO", "EN_MANTENIMIENTO", "EN_TRANSITO",
            "INOPERATIVO", "FUERA_SERVICIO");
    private static final Map<String, String> ACTIONS = Map.of(
            "REGISTERED", "ASSET_REGISTERED", "UPDATED", "ASSET_UPDATED",
            "STATUS_CHANGED", "ASSET_STATUS_CHANGED");

    public RecordTraceabilityAuditCommand validateAndExtract(String routingKey, JsonNode envelope) {
        if (!ROUTING_KEY.equals(routingKey)) TraceabilityEventValidation.fail("routing key");
        TraceabilityEventValidation.envelope(envelope, "AssetChanged", "Asset");
        UUID aggregateId = TraceabilityEventValidation.uuid(envelope, "aggregateId");
        JsonNode payload = envelope.path("payload");
        TraceabilityEventValidation.exact(payload, TraceabilityEventValidation.PAYLOAD, "payload");
        String changeType = TraceabilityEventValidation.text(payload, "changeType");
        String action = ACTIONS.get(changeType);
        if (action == null) TraceabilityEventValidation.fail("changeType");
        UUID actor = TraceabilityEventValidation.uuid(payload, "actorUserId");
        JsonNode beforeNode = payload.get("before");
        if ("REGISTERED".equals(changeType)) {
            if (beforeNode == null || !beforeNode.isNull()) TraceabilityEventValidation.fail("registered before");
        } else if (beforeNode == null || beforeNode.isNull()) {
            TraceabilityEventValidation.fail("before");
        }
        Map<String, Object> before = beforeNode == null || beforeNode.isNull() ? null : snapshot(beforeNode);
        Map<String, Object> after = snapshot(payload.path("after"));
        if (!aggregateId.toString().equals(after.get("id"))) TraceabilityEventValidation.fail("aggregateId");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("changeType", changeType);
        details.put("before", before);
        details.put("after", after);
        return new RecordTraceabilityAuditCommand(actor, "AssetChanged", "Asset", aggregateId,
                TraceabilityEventValidation.uuid(envelope, "correlationId"),
                TraceabilityEventValidation.instant(envelope, "occurredAt"), action, details);
    }

    private Map<String, Object> snapshot(JsonNode node) {
        TraceabilityEventValidation.exact(node, SNAPSHOT, "asset snapshot");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", TraceabilityEventValidation.uuid(node, "id").toString());
        value.put("productId", TraceabilityEventValidation.uuid(node, "productId").toString());
        value.put("assetCode", TraceabilityEventValidation.boundedText(node, "assetCode", 80));
        value.put("serialNumber", TraceabilityEventValidation.boundedText(node, "serialNumber", 160));
        value.put("brand", TraceabilityEventValidation.nullableText(node, "brand", 100));
        value.put("model", TraceabilityEventValidation.nullableText(node, "model", 100));
        String status = TraceabilityEventValidation.text(node, "operationalStatus");
        if (!STATUSES.contains(status)) TraceabilityEventValidation.fail("operationalStatus");
        value.put("operationalStatus", status);
        JsonNode technical = node.get("technicalSnapshot");
        if (technical == null || !technical.isObject()) TraceabilityEventValidation.fail("technicalSnapshot");
        TraceabilityEventValidation.assertNoSecrets(technical);
        value.put("version", TraceabilityEventValidation.version(node));
        value.put("createdAt", TraceabilityEventValidation.instant(node, "createdAt").toString());
        value.put("updatedAt", TraceabilityEventValidation.instant(node, "updatedAt").toString());
        return value;
    }
}
