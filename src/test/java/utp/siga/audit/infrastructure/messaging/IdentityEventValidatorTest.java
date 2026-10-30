package utp.siga.audit.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IdentityEventValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final IdentityEventValidator validator = new IdentityEventValidator(mapper);

    @ParameterizedTest
    @MethodSource("contracts")
    void acceptsCanonicalV1AndV2Contracts(String routingKey, String eventType, int version,
            String aggregateType, String action) {
        UUID aggregateId = UUID.randomUUID();
        ValidatedIdentityEvent event = validator.validateAndExtract(routingKey,
                envelope(eventType, version, aggregateType, aggregateId));

        assertThat(event.command().eventType()).isEqualTo(eventType);
        assertThat(event.command().aggregateType()).isEqualTo(aggregateType);
        assertThat(event.command().aggregateId()).isEqualTo(aggregateId);
        assertThat(event.command().action()).isEqualTo(action);
        if (version == 1) {
            assertThat(event.command().details()).containsEntry("legacy", true)
                    .containsEntry("legacyId", aggregateId.toString());
        } else {
            assertThat(event.command().details()).containsKeys("before", "after");
        }
    }

    @Test
    void legacyRoleChangedRemainsAmbiguousWithoutSubtypeInference() {
        UUID aggregateId = UUID.randomUUID();
        var event = validator.validateAndExtract("iam.RoleChanged",
                envelope("RoleChanged", 1, "Identity", aggregateId));

        assertThat(event.command().action()).isEqualTo("IDENTITY_ROLE_CHANGED_LEGACY");
        assertThat(event.command().details()).containsOnlyKeys("legacy", "legacyId");
    }

    @Test
    void rejectsUnknownRoutingAndCrossFieldIdentityMismatch() {
        ObjectNode valid = envelope("UserCreated", 2, "User", UUID.randomUUID());
        assertThatThrownBy(() -> validator.validateAndExtract("iam.unknown.v2", valid))
                .isInstanceOf(IllegalArgumentException.class);

        valid.withObject("/payload").put("userId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> validator.validateAndExtract("iam.user.created.v2", valid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity mismatch");
    }

    static Stream<Arguments> contracts() {
        return Stream.of(
                Arguments.of("iam.UserCreated", "UserCreated", 1, "Identity", "USER_CREATED"),
                Arguments.of("iam.UserUpdated", "UserUpdated", 1, "Identity", "USER_UPDATED"),
                Arguments.of("iam.UserDisabled", "UserDisabled", 1, "Identity", "USER_DISABLED"),
                Arguments.of("iam.RoleChanged", "RoleChanged", 1, "Identity", "IDENTITY_ROLE_CHANGED_LEGACY"),
                Arguments.of("iam.user.created.v2", "UserCreated", 2, "User", "USER_CREATED"),
                Arguments.of("iam.user.updated.v2", "UserUpdated", 2, "User", "USER_UPDATED"),
                Arguments.of("iam.user.disabled.v2", "UserDisabled", 2, "User", "USER_DISABLED"),
                Arguments.of("iam.user.roles.changed.v2", "UserRolesChanged", 2, "User", "USER_ROLES_CHANGED"),
                Arguments.of("iam.role.created.v2", "RoleCreated", 2, "Role", "ROLE_CREATED"),
                Arguments.of("iam.role.permissions.changed.v2", "RolePermissionsChanged", 2, "Role",
                        "ROLE_PERMISSIONS_CHANGED"));
    }

    private ObjectNode envelope(String eventType, int version, String aggregateType, UUID aggregateId) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", eventType);
        envelope.put("schemaVersion", version);
        envelope.put("producer", "identity-service");
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId.toString());
        envelope.put("correlationId", UUID.randomUUID().toString());
        envelope.put("occurredAt", "2026-10-30T12:00:00Z");
        ObjectNode payload = envelope.putObject("payload");
        if (version == 1) {
            payload.put("id", aggregateId.toString());
            payload.putNull("actorId");
            return envelope;
        }

        boolean user = "User".equals(aggregateType);
        payload.put(user ? "userId" : "roleId", aggregateId.toString());
        payload.put("actorUserId", UUID.randomUUID().toString());
        if (eventType.endsWith("Created")) payload.putNull("before");
        else payload.set("before", user ? userSnapshot(aggregateId, true) : roleSnapshot(aggregateId));
        ObjectNode after = user ? userSnapshot(aggregateId, !"UserDisabled".equals(eventType))
                : roleSnapshot(aggregateId);
        payload.set("after", after);
        return envelope;
    }

    private ObjectNode userSnapshot(UUID id, boolean active) {
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("id", id.toString());
        snapshot.put("username", "audit.user");
        snapshot.put("email", "audit.user@example.test");
        snapshot.put("active", active);
        snapshot.put("mfaEnabled", true);
        ArrayNode roles = snapshot.putArray("roleIds");
        roles.add(UUID.randomUUID().toString());
        return snapshot;
    }

    private ObjectNode roleSnapshot(UUID id) {
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("id", id.toString());
        snapshot.put("code", "AUDITOR");
        snapshot.put("name", "Auditor");
        snapshot.put("active", true);
        snapshot.putArray("permissionIds").add(UUID.randomUUID().toString());
        return snapshot;
    }
}
