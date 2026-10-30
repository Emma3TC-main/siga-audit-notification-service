package utp.siga.audit.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import utp.siga.audit.application.command.RecordAuditEventCommand;

@Component
public class MovementConfirmedEventValidator {

    public static final String EXPECTED_EVENT_TYPE = "MovementConfirmed";
    public static final int EXPECTED_SCHEMA_VERSION = 1;
    public static final String EXPECTED_AGGREGATE_TYPE = "Movement";
    public static final String EXPECTED_PRODUCER = "inventory-service";
    public static final String EXPECTED_ROUTING_KEY = "inventory.movement.confirmed.v1";

    private static final Set<String> ALLOWED_MOVEMENT_TYPES = Set.of(
            "ENTRY", "EXIT", "TRANSFER", "ADJUST_POSITIVE", "ADJUST_NEGATIVE");

    private static final Set<String> FORBIDDEN_SECRET_KEYS = Set.of(
            "jwt", "token", "password", "secret", "otp", "authorization", "accesstoken", "refreshtoken");

    public RecordAuditEventCommand validateAndExtract(String routingKey, JsonNode envelope) {
        if (!EXPECTED_ROUTING_KEY.equals(routingKey)) {
            throw new IllegalArgumentException("Unsupported routing key: " + routingKey);
        }

        if (envelope == null || !envelope.isObject()) {
            throw new IllegalArgumentException("Event envelope must be a JSON object");
        }

        // Envelope validation
        UUID eventId = parseUuid(envelope.path("eventId"), "eventId");
        String eventType = requiredText(envelope.path("eventType"), "eventType");
        if (!EXPECTED_EVENT_TYPE.equals(eventType)) {
            throw new IllegalArgumentException("Unsupported eventType: " + eventType);
        }

        JsonNode schemaVersionNode = envelope.path("schemaVersion");
        if (!schemaVersionNode.isIntegralNumber() || schemaVersionNode.intValue() != EXPECTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported schemaVersion: " + schemaVersionNode.asText());
        }

        String aggregateType = requiredText(envelope.path("aggregateType"), "aggregateType");
        if (!EXPECTED_AGGREGATE_TYPE.equals(aggregateType)) {
            throw new IllegalArgumentException("Unsupported aggregateType: " + aggregateType);
        }

        UUID aggregateId = parseUuid(envelope.path("aggregateId"), "aggregateId");
        Instant occurredAt = parseInstant(envelope.path("occurredAt"), "occurredAt");
        UUID correlationId = parseUuid(envelope.path("correlationId"), "correlationId");

        if (envelope.has("causationId") && !envelope.path("causationId").isNull()) {
            parseUuid(envelope.path("causationId"), "causationId");
        }

        String producer = requiredText(envelope.path("producer"), "producer");
        if (!EXPECTED_PRODUCER.equals(producer)) {
            throw new IllegalArgumentException("Unsupported producer: " + producer);
        }

        // Payload validation
        JsonNode payload = envelope.path("payload");
        if (!payload.isObject()) {
            throw new IllegalArgumentException("Payload must be a JSON object");
        }

        JsonNode movement = payload.path("movement");
        if (!movement.isObject()) {
            throw new IllegalArgumentException("payload.movement must be a JSON object");
        }

        UUID movementId = parseUuid(movement.path("id"), "movement.id");
        if (!aggregateId.equals(movementId)) {
            throw new IllegalArgumentException("aggregateId does not match movement.id");
        }

        String movementCode = requiredText(movement.path("movementCode"), "movement.movementCode");
        String movementType = requiredText(movement.path("movementType"), "movement.movementType");
        if (!ALLOWED_MOVEMENT_TYPES.contains(movementType)) {
            throw new IllegalArgumentException("Invalid movementType: " + movementType);
        }

        String status = requiredText(movement.path("status"), "movement.status");
        if (!"CONFIRMED".equals(status)) {
            throw new IllegalArgumentException("Invalid movement status: " + status);
        }

        UUID registeredByUserId = parseUuid(movement.path("registeredByUserId"), "movement.registeredByUserId");
        UUID confirmedByUserId = parseUuid(movement.path("confirmedByUserId"), "movement.confirmedByUserId");

        if (!movement.has("isSensitive") || !movement.path("isSensitive").isBoolean()) {
            throw new IllegalArgumentException("movement.isSensitive must be a boolean");
        }
        boolean isSensitive = movement.path("isSensitive").asBoolean();

        parseInstant(movement.path("confirmedAt"), "movement.confirmedAt");
        BigDecimal totalBaseCostPen = parseNonNegativeDecimal(
                movement.path("totalBaseCostPen"), "movement.totalBaseCostPen", true);

        // Validate details array
        JsonNode detailsNode = payload.path("details");
        if (!detailsNode.isArray() || detailsNode.isEmpty()) {
            throw new IllegalArgumentException("payload.details must be a non-empty array");
        }
        List<Map<String, Object>> detailsList = new ArrayList<>();
        for (JsonNode d : detailsNode) {
            parseUuid(d.path("id"), "details[].id");
            if (!d.path("lineNumber").isIntegralNumber() || d.path("lineNumber").intValue() < 1) {
                throw new IllegalArgumentException("details[].lineNumber must be an integer >= 1");
            }
            parseUuid(d.path("productId"), "details[].productId");
            BigDecimal enteredQuantity = parseStrictlyPositiveDecimal(
                    d.path("enteredQuantity"), "details[].enteredQuantity");
            requiredText(d.path("unitCode"), "details[].unitCode");
            BigDecimal conversionFactor = parseStrictlyPositiveDecimal(
                    d.path("conversionFactor"), "details[].conversionFactor");
            BigDecimal stockQuantity = parseStrictlyPositiveDecimal(
                    d.path("stockQuantity"), "details[].stockQuantity");

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", d.path("id").asText());
            item.put("lineNumber", d.path("lineNumber").intValue());
            item.put("productId", d.path("productId").asText());
            item.put("enteredQuantity", enteredQuantity);
            item.put("unitCode", d.path("unitCode").asText());
            item.put("conversionFactor", conversionFactor);
            item.put("stockQuantity", stockQuantity);
            putUuidIfPresent(d, "originLocationId", "details[].originLocationId", item);
            putUuidIfPresent(d, "destinationLocationId", "details[].destinationLocationId", item);
            putUuidIfPresent(d, "lotId", "details[].lotId", item);
            putUuidIfPresent(d, "assetId", "details[].assetId", item);
            if (d.has("baseUnitCostPen") && !d.path("baseUnitCostPen").isNull()) {
                item.put("baseUnitCostPen", parseNonNegativeDecimal(
                        d.path("baseUnitCostPen"), "details[].baseUnitCostPen", true));
            }
            if (d.has("appliedAvgCostPen") && !d.path("appliedAvgCostPen").isNull()) {
                item.put("appliedAvgCostPen", parseNonNegativeDecimal(
                        d.path("appliedAvgCostPen"), "details[].appliedAvgCostPen", true));
            }
            detailsList.add(item);
        }

        // Validate stockBalances array
        JsonNode stockBalancesNode = payload.path("stockBalances");
        if (!stockBalancesNode.isArray() || stockBalancesNode.isEmpty()) {
            throw new IllegalArgumentException("payload.stockBalances must be a non-empty array");
        }
        List<Map<String, Object>> stockBalancesList = new ArrayList<>();
        for (JsonNode s : stockBalancesNode) {
            parseUuid(s.path("stockBalanceId"), "stockBalances[].stockBalanceId");
            parseUuid(s.path("productId"), "stockBalances[].productId");
            parseUuid(s.path("locationId"), "stockBalances[].locationId");
            BigDecimal quantity = parseNonNegativeDecimal(
                    s.path("quantity"), "stockBalances[].quantity", false);
            BigDecimal avgCostPen = parseNonNegativeDecimal(
                    s.path("avgCostPen"), "stockBalances[].avgCostPen", true);
            BigDecimal minStock = parseNonNegativeDecimal(
                    s.path("minStock"), "stockBalances[].minStock", true);

            Map<String, Object> balance = new LinkedHashMap<>();
            balance.put("stockBalanceId", s.path("stockBalanceId").asText());
            balance.put("productId", s.path("productId").asText());
            balance.put("locationId", s.path("locationId").asText());
            putUuidIfPresent(s, "lotId", "stockBalances[].lotId", balance);
            putUuidIfPresent(s, "assetId", "stockBalances[].assetId", balance);
            balance.put("quantity", quantity);
            balance.put("avgCostPen", avgCostPen);
            balance.put("minStock", minStock);
            stockBalancesList.add(balance);
        }

        // Verify no forbidden keys exist in envelope or payload
        assertNoSecrets(envelope);

        // Build structured allowed details
        Map<String, Object> allowedDetails = new LinkedHashMap<>();
        allowedDetails.put("movementCode", movementCode);
        allowedDetails.put("movementType", movementType);
        allowedDetails.put("status", status);
        allowedDetails.put("registeredByUserId", registeredByUserId.toString());
        allowedDetails.put("confirmedByUserId", confirmedByUserId.toString());
        allowedDetails.put("isSensitive", isSensitive);
        allowedDetails.put("totalBaseCostPen", totalBaseCostPen);

        putUuidIfPresent(movement, "requestedByUserId", "movement.requestedByUserId", allowedDetails);
        putStringIfPresent(movement, "costCenterCode", allowedDetails);
        putStringIfPresent(movement, "costCenterNameSnapshot", allowedDetails);
        putUuidIfPresent(movement, "supplierId", "movement.supplierId", allowedDetails);
        putStringIfPresent(movement, "supplierNameSnapshot", allowedDetails);
        putStringIfPresent(movement, "supplierTaxIdSnapshot", allowedDetails);
        putStringIfPresent(movement, "referenceDocType", allowedDetails);
        putStringIfPresent(movement, "referenceDocNumber", allowedDetails);
        putStringIfPresent(movement, "reason", allowedDetails);
        putStringIfPresent(movement, "sensitivityReason", allowedDetails);
        putUuidIfPresent(movement, "authorizedByUserId", "movement.authorizedByUserId", allowedDetails);
        putStringIfPresent(movement, "confirmedAt", allowedDetails);

        allowedDetails.put("details", detailsList);
        allowedDetails.put("stockBalances", stockBalancesList);

        return new RecordAuditEventCommand(
                confirmedByUserId,
                aggregateId,
                correlationId,
                occurredAt,
                allowedDetails);
    }

    private void assertNoSecrets(JsonNode node) {
        if (node.isObject()) {
            var fields = node.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (FORBIDDEN_SECRET_KEYS.contains(field.toLowerCase())) {
                    throw new IllegalArgumentException("Forbidden secret key detected: " + field);
                }
                assertNoSecrets(node.get(field));
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                assertNoSecrets(child);
            }
        }
    }

    private static UUID parseUuid(JsonNode node, String fieldName) {
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException(fieldName + " must be a UUID string");
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(fieldName + " is not a valid UUID: " + node.asText());
        }
    }

    private static Instant parseInstant(JsonNode node, String fieldName) {
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException(fieldName + " must be an ISO-8601 date-time string");
        }
        try {
            return Instant.parse(node.asText());
        } catch (Exception e) {
            throw new IllegalArgumentException(fieldName + " is not a valid ISO-8601 date-time: " + node.asText());
        }
    }

    private static String requiredText(JsonNode node, String fieldName) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be a non-empty string");
        }
        return node.asText();
    }

    private static BigDecimal parseStrictlyPositiveDecimal(JsonNode node, String fieldName) {
        BigDecimal value = parseDecimal(node, fieldName);
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than zero");
        }
        return value;
    }

    private static BigDecimal parseNonNegativeDecimal(JsonNode node, String fieldName, boolean maximumSixDecimals) {
        BigDecimal value = parseDecimal(node, fieldName);
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(fieldName + " must be non-negative");
        }
        if (maximumSixDecimals && Math.max(0, value.stripTrailingZeros().scale()) > 6) {
            throw new IllegalArgumentException(fieldName + " must use at most 6 decimal places");
        }
        return value;
    }

    private static BigDecimal parseDecimal(JsonNode node, String fieldName) {
        if (node == null || !node.isNumber()) {
            throw new IllegalArgumentException(fieldName + " must be a number");
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + " is not a valid decimal number: " + node.asText());
        }
    }

    private static void putUuidIfPresent(
            JsonNode source, String field, String fieldName, Map<String, Object> target) {
        if (source.has(field) && !source.path(field).isNull()) {
            target.put(field, parseUuid(source.path(field), fieldName).toString());
        }
    }

    private static void putStringIfPresent(JsonNode source, String field, Map<String, Object> target) {
        if (source.has(field) && !source.path(field).isNull()) {
            if (!source.path(field).isTextual()) {
                throw new IllegalArgumentException(field + " must be a string");
            }
            target.put(field, source.path(field).asText());
        }
    }
}
