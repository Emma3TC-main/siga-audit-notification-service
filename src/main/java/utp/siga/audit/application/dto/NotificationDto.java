package utp.siga.audit.application.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationDto(
        UUID id,
        UUID recipientUserId,
        String type,
        String title,
        String message,
        String severity,
        String referenceType,
        UUID referenceId,
        Instant readAt,
        Instant createdAt) {
}
