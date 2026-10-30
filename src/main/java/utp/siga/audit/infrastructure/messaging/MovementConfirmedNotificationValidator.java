package utp.siga.audit.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import utp.siga.audit.application.command.RecordAuditEventCommand;
import utp.siga.audit.application.command.RecordMovementNotificationCommand;

@Component
public class MovementConfirmedNotificationValidator {

    private final MovementConfirmedEventValidator canonicalValidator;

    public MovementConfirmedNotificationValidator(MovementConfirmedEventValidator canonicalValidator) {
        this.canonicalValidator = Objects.requireNonNull(canonicalValidator, "canonicalValidator must not be null");
    }

    public RecordMovementNotificationCommand validateAndExtract(String routingKey, JsonNode envelope) {
        RecordAuditEventCommand validated = canonicalValidator.validateAndExtract(routingKey, envelope);
        Map<String, Object> details = validated.details();
        return new RecordMovementNotificationCommand(
                UUID.fromString(requiredString(details, "registeredByUserId")),
                validated.aggregateId(),
                requiredString(details, "movementCode"),
                requiredString(details, "movementType"),
                validated.occurredAt());
    }

    private static String requiredString(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-empty string");
        }
        return text;
    }
}
