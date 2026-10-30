package utp.siga.audit.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Notification(
        UUID id,
        UUID recipientUserId,
        String type,
        String title,
        String message,
        String referenceType,
        UUID referenceId,
        Instant createdAt,
        Instant readAt) {

    public Notification {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(recipientUserId, "recipientUserId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(referenceType, "referenceType must not be null");
        Objects.requireNonNull(referenceId, "referenceId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
