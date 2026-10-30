package utp.siga.audit.application.command;

import java.time.Instant;
import java.util.UUID;

public record RecordMovementNotificationCommand(
        UUID recipientUserId,
        UUID movementId,
        String movementCode,
        String movementType,
        Instant occurredAt) {
}
