package utp.siga.audit.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import utp.siga.audit.application.command.RecordTraceabilityAuditCommand;

@Component
public class LotChangedEventValidator {
    public static final String ROUTING_KEY = "inventory.lot.changed.v1";
    private static final Set<String> SNAPSHOT = Set.of("id", "productId", "lotNumber", "heatNumber",
            "manufacturedAt", "receivedAt", "expiresAt", "status", "version", "createdAt", "updatedAt");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "QUARANTINED", "EXPIRED", "DEPLETED", "BLOCKED");
    private static final Map<String, String> ACTIONS = Map.of(
            "CREATED", "LOT_CREATED", "UPDATED", "LOT_UPDATED", "STATUS_CHANGED", "LOT_STATUS_CHANGED");

    public RecordTraceabilityAuditCommand validateAndExtract(String routingKey, JsonNode envelope) {
        if (!ROUTING_KEY.equals(routingKey)) TraceabilityEventValidation.fail("routing key");
        TraceabilityEventValidation.envelope(envelope, "LotChanged", "Lot");
        UUID aggregateId = TraceabilityEventValidation.uuid(envelope, "aggregateId");
        JsonNode payload = envelope.path("payload");
        TraceabilityEventValidation.exact(payload, TraceabilityEventValidation.PAYLOAD, "payload");
        String changeType = TraceabilityEventValidation.text(payload, "changeType");
        String action = ACTIONS.get(changeType);
        if (action == null) TraceabilityEventValidation.fail("changeType");
        UUID actor = TraceabilityEventValidation.nullableUuid(payload, "actorUserId");
        JsonNode beforeNode = payload.get("before");
        if ("CREATED".equals(changeType)) {
            if (beforeNode == null || !beforeNode.isNull()) TraceabilityEventValidation.fail("created before");
        } else if (beforeNode == null || beforeNode.isNull()) {
            TraceabilityEventValidation.fail("before");
        }
        Map<String, Object> before = beforeNode == null || beforeNode.isNull() ? null : snapshot(beforeNode);
        Map<String, Object> after = snapshot(payload.path("after"));
        if (!aggregateId.toString().equals(after.get("id"))) TraceabilityEventValidation.fail("aggregateId");
        return command(envelope, aggregateId, actor, action, changeType, before, after);
    }

    private Map<String, Object> snapshot(JsonNode node) {
        TraceabilityEventValidation.exact(node, SNAPSHOT, "lot snapshot");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", TraceabilityEventValidation.uuid(node, "id").toString());
        value.put("productId", TraceabilityEventValidation.uuid(node, "productId").toString());
        value.put("lotNumber", TraceabilityEventValidation.nullableText(node, "lotNumber", 120));
        value.put("heatNumber", TraceabilityEventValidation.nullableText(node, "heatNumber", 120));
        value.put("manufacturedAt", TraceabilityEventValidation.nullableDate(node, "manufacturedAt"));
        value.put("receivedAt", TraceabilityEventValidation.nullableDate(node, "receivedAt"));
        value.put("expiresAt", TraceabilityEventValidation.nullableDate(node, "expiresAt"));
        String status = TraceabilityEventValidation.text(node, "status");
        if (!STATUSES.contains(status)) TraceabilityEventValidation.fail("status");
        value.put("status", status);
        value.put("version", TraceabilityEventValidation.version(node));
        value.put("createdAt", TraceabilityEventValidation.instant(node, "createdAt").toString());
        value.put("updatedAt", TraceabilityEventValidation.instant(node, "updatedAt").toString());
        return value;
    }

    private RecordTraceabilityAuditCommand command(JsonNode envelope, UUID aggregateId, UUID actor,
            String action, String changeType, Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("changeType", changeType);
        details.put("before", before);
        details.put("after", after);
        return new RecordTraceabilityAuditCommand(actor, "LotChanged", "Lot", aggregateId,
                TraceabilityEventValidation.uuid(envelope, "correlationId"),
                TraceabilityEventValidation.instant(envelope, "occurredAt"), action, details);
    }
}
