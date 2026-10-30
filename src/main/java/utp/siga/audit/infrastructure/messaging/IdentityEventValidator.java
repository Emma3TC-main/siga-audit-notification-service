package utp.siga.audit.infrastructure.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import utp.siga.audit.application.command.RecordIdentityAuditCommand;

@Component
public class IdentityEventValidator {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private static final Map<String, Contract> CONTRACTS = Map.ofEntries(
            Map.entry("iam.UserCreated", new Contract("UserCreated", 1, "Identity", "USER_CREATED",
                    "iam-user-created-v1.schema.json")),
            Map.entry("iam.UserUpdated", new Contract("UserUpdated", 1, "Identity", "USER_UPDATED",
                    "iam-user-updated-v1.schema.json")),
            Map.entry("iam.UserDisabled", new Contract("UserDisabled", 1, "Identity", "USER_DISABLED",
                    "iam-user-disabled-v1.schema.json")),
            Map.entry("iam.RoleChanged", new Contract("RoleChanged", 1, "Identity",
                    "IDENTITY_ROLE_CHANGED_LEGACY", "iam-role-changed-v1.schema.json")),
            Map.entry("iam.user.created.v2", new Contract("UserCreated", 2, "User", "USER_CREATED",
                    "iam-user-created-v2.schema.json")),
            Map.entry("iam.user.updated.v2", new Contract("UserUpdated", 2, "User", "USER_UPDATED",
                    "iam-user-updated-v2.schema.json")),
            Map.entry("iam.user.disabled.v2", new Contract("UserDisabled", 2, "User", "USER_DISABLED",
                    "iam-user-disabled-v2.schema.json")),
            Map.entry("iam.user.roles.changed.v2", new Contract("UserRolesChanged", 2, "User",
                    "USER_ROLES_CHANGED", "iam-user-roles-changed-v2.schema.json")),
            Map.entry("iam.role.created.v2", new Contract("RoleCreated", 2, "Role", "ROLE_CREATED",
                    "iam-role-created-v2.schema.json")),
            Map.entry("iam.role.permissions.changed.v2", new Contract("RolePermissionsChanged", 2, "Role",
                    "ROLE_PERMISSIONS_CHANGED", "iam-role-permissions-changed-v2.schema.json")));

    private final ObjectMapper mapper;
    private final Map<String, JsonSchema> schemas;

    public IdentityEventValidator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        Map<String, JsonSchema> loaded = new LinkedHashMap<>();
        CONTRACTS.values().stream().map(Contract::schema).distinct().forEach(name -> {
            String path = "/schemas/identity/" + name;
            try (InputStream resource = IdentityEventValidator.class.getResourceAsStream(path)) {
                if (resource == null) {
                    throw new IllegalStateException("Missing bundled canonical schema " + path);
                }
                loaded.put(name, factory.getSchema(resource));
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot load canonical schema " + path, exception);
            }
        });
        this.schemas = Map.copyOf(loaded);
    }

    public ValidatedIdentityEvent validateAndExtract(String routingKey, JsonNode envelope) {
        Contract contract = CONTRACTS.get(routingKey);
        if (contract == null) {
            throw new IllegalArgumentException("Unsupported IAM routing key");
        }
        var errors = schemas.get(contract.schema()).validate(envelope);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("IAM event does not match canonical schema: " + errors.iterator().next());
        }

        UUID eventId = uuid(envelope, "eventId");
        UUID aggregateId = uuid(envelope, "aggregateId");
        UUID correlationId = uuid(envelope, "correlationId");
        Instant occurredAt = Instant.parse(envelope.path("occurredAt").textValue());
        JsonNode payload = envelope.path("payload");

        UUID actorId;
        Map<String, Object> details = new LinkedHashMap<>();
        if (contract.version() == 1) {
            UUID payloadId = uuid(payload, "id");
            if (!aggregateId.equals(payloadId)) {
                throw new IllegalArgumentException("Legacy IAM aggregateId/payload.id mismatch");
            }
            actorId = nullableUuid(payload, "actorId");
            details.put("legacy", true);
            details.put("legacyId", payloadId.toString());
        } else {
            String idField = "User".equals(contract.aggregateType()) ? "userId" : "roleId";
            UUID payloadId = uuid(payload, idField);
            if (!aggregateId.equals(payloadId)) {
                throw new IllegalArgumentException("IAM aggregateId/payload identity mismatch");
            }
            actorId = uuid(payload, "actorUserId");
            details.put("before", toMapOrNull(payload.get("before")));
            details.put("after", toMapOrNull(payload.get("after")));
        }

        return new ValidatedIdentityEvent(eventId, new RecordIdentityAuditCommand(
                contract.eventType(), actorId, contract.aggregateType(), aggregateId, contract.action(),
                correlationId, occurredAt, Collections.unmodifiableMap(details)));
    }

    private Map<String, Object> toMapOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : mapper.convertValue(node, MAP);
    }

    private static UUID uuid(JsonNode node, String field) {
        return UUID.fromString(node.path(field).textValue());
    }

    private static UUID nullableUuid(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : UUID.fromString(value.textValue());
    }

    private record Contract(String eventType, int version, String aggregateType, String action, String schema) { }
}
