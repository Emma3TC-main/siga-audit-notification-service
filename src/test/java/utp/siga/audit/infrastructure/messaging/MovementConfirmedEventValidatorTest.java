package utp.siga.audit.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import utp.siga.audit.application.command.RecordAuditEventCommand;

class MovementConfirmedEventValidatorTest {

    private MovementConfirmedEventValidator validator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        validator = new MovementConfirmedEventValidator();
        mapper = new ObjectMapper();
    }

    @Test
    void validMovementConfirmedMapping() {
        UUID eventId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        ObjectNode envelope = createValidEnvelope(eventId, movementId, actorId, correlationId);

        RecordAuditEventCommand command = validator.validateAndExtract("inventory.movement.confirmed.v1", envelope);

        assertNotNull(command);
        assertEquals(actorId, command.actorId());
        assertEquals(movementId, command.aggregateId());
        assertEquals(correlationId, command.correlationId());
        assertNotNull(command.occurredAt());
        assertNotNull(command.details());
        assertEquals("MOV-2026-001", command.details().get("movementCode"));
        assertEquals("ENTRY", command.details().get("movementType"));
        assertEquals("CONFIRMED", command.details().get("status"));
        assertEquals(1500.50, ((Number) command.details().get("totalBaseCostPen")).doubleValue(), 0.001);
    }

    @Test
    void unsupportedRoutingKeyThrowsException() {
        ObjectNode envelope = createValidEnvelope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () ->
                validator.validateAndExtract("other.routing.key", envelope));
    }

    @Test
    void missingEventIdThrowsException() {
        ObjectNode envelope = createValidEnvelope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        envelope.remove("eventId");
        assertThrows(IllegalArgumentException.class, () ->
                validator.validateAndExtract("inventory.movement.confirmed.v1", envelope));
    }

    @Test
    void invalidEventIdThrowsException() {
        ObjectNode envelope = createValidEnvelope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        envelope.put("eventId", "not-a-valid-uuid");
        assertThrows(IllegalArgumentException.class, () ->
                validator.validateAndExtract("inventory.movement.confirmed.v1", envelope));
    }

    @Test
    void unsupportedEventTypeThrowsException() {
        ObjectNode envelope = createValidEnvelope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        envelope.put("eventType", "UnknownEvent");
        assertThrows(IllegalArgumentException.class, () ->
                validator.validateAndExtract("inventory.movement.confirmed.v1", envelope));
    }

    @Test
    void unsupportedSchemaVersionThrowsException() {
        ObjectNode envelope = createValidEnvelope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        envelope.put("schemaVersion", 2);
        assertThrows(IllegalArgumentException.class, () ->
                validator.validateAndExtract("inventory.movement.confirmed.v1", envelope));
    }

    @Test
    void unsupportedProducerThrowsException() {
        ObjectNode envelope = createValidEnvelope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        envelope.put("producer", "unknown-service");
        assertThrows(IllegalArgumentException.class, () ->
                validator.validateAndExtract("inventory.movement.confirmed.v1", envelope));
    }

    @Test
    void missingPayloadThrowsException() {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "MovementConfirmed");
        envelope.put("schemaVersion", 1);
        envelope.put("aggregateType", "Movement");
        envelope.put("aggregateId", UUID.randomUUID().toString());
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("correlationId", UUID.randomUUID().toString());
        envelope.put("producer", "inventory-service");
        assertThrows(IllegalArgumentException.class, () ->
                validator.validateAndExtract("inventory.movement.confirmed.v1", envelope));
    }

    @Test
    void payloadContainingForbiddenSecretsThrowsException() {
        ObjectNode envelope = createValidEnvelope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        ((ObjectNode) envelope.get("payload")).put("jwt", "eyJhbGciOi...");
        assertThrows(IllegalArgumentException.class, () ->
                validator.validateAndExtract("inventory.movement.confirmed.v1", envelope));
    }

    @Test
    void payloadContainingOtpSecretThrowsException() {
        ObjectNode envelope = createValidEnvelope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        ((ObjectNode) envelope.get("payload")).put("otp", "123456");
        assertThrows(IllegalArgumentException.class, () ->
                validator.validateAndExtract("inventory.movement.confirmed.v1", envelope));
    }

    @Test
    void emptyDetailsArrayThrowsException() {
        ObjectNode envelope = createValidEnvelope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        ((ObjectNode) envelope.get("payload")).putArray("details");
        assertThrows(IllegalArgumentException.class, () ->
                validator.validateAndExtract("inventory.movement.confirmed.v1", envelope));
    }

    @Test
    void emptyStockBalancesArrayThrowsException() {
        ObjectNode envelope = createValidEnvelope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        ((ObjectNode) envelope.get("payload")).putArray("stockBalances");
        assertThrows(IllegalArgumentException.class, () ->
                validator.validateAndExtract("inventory.movement.confirmed.v1", envelope));
    }

    @Test
    void zeroEnteredQuantityViolatesCanonicalExclusiveMinimum() {
        ObjectNode envelope = createValidEnvelope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        ((ObjectNode) envelope.path("payload").path("details").get(0)).put("enteredQuantity", 0);

        assertThrows(IllegalArgumentException.class, () ->
                validator.validateAndExtract("inventory.movement.confirmed.v1", envelope));
    }

    @Test
    void textualDecimalIsRejectedBecauseCanonicalSchemaRequiresNumber() {
        ObjectNode envelope = createValidEnvelope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        ((ObjectNode) envelope.path("payload").path("movement")).put("totalBaseCostPen", "1500.50");

        assertThrows(IllegalArgumentException.class, () ->
                validator.validateAndExtract("inventory.movement.confirmed.v1", envelope));
    }

    @Test
    void malformedOptionalUuidIsRejected() {
        ObjectNode envelope = createValidEnvelope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        ((ObjectNode) envelope.path("payload").path("movement")).put("supplierId", "not-a-uuid");

        assertThrows(IllegalArgumentException.class, () ->
                validator.validateAndExtract("inventory.movement.confirmed.v1", envelope));
    }

    @Test
    void excessiveCostPrecisionIsRejected() {
        ObjectNode envelope = createValidEnvelope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        ((ObjectNode) envelope.path("payload").path("movement")).put("totalBaseCostPen", new BigDecimal("1.0000001"));

        assertThrows(IllegalArgumentException.class, () ->
                validator.validateAndExtract("inventory.movement.confirmed.v1", envelope));
    }

    private ObjectNode createValidEnvelope(UUID eventId, UUID movementId, UUID actorId, UUID correlationId) {
        ObjectNode envelope = mapper.createObjectNode();
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
        movement.put("movementCode", "MOV-2026-001");
        movement.put("movementType", "ENTRY");
        movement.put("status", "CONFIRMED");
        movement.put("registeredByUserId", UUID.randomUUID().toString());
        movement.put("confirmedByUserId", actorId.toString());
        movement.put("isSensitive", false);
        movement.put("confirmedAt", "2026-10-14T20:00:00Z");
        movement.put("totalBaseCostPen", new BigDecimal("1500.500000"));

        ArrayNode details = payload.putArray("details");
        ObjectNode item = details.addObject();
        item.put("id", UUID.randomUUID().toString());
        item.put("lineNumber", 1);
        item.put("productId", UUID.randomUUID().toString());
        item.put("enteredQuantity", 10.0);
        item.put("unitCode", "KG");
        item.put("conversionFactor", 1.0);
        item.put("stockQuantity", 10.0);
        item.put("baseUnitCostPen", 150.05);

        ArrayNode stock = payload.putArray("stockBalances");
        ObjectNode balance = stock.addObject();
        balance.put("stockBalanceId", UUID.randomUUID().toString());
        balance.put("productId", UUID.randomUUID().toString());
        balance.put("locationId", UUID.randomUUID().toString());
        balance.put("quantity", 50.0);
        balance.put("avgCostPen", 150.05);
        balance.put("minStock", 5.0);

        return envelope;
    }
}
