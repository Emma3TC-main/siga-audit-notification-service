package utp.siga.audit.application.usecase;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.audit.application.command.RecordMovementNotificationCommand;
import utp.siga.audit.application.dto.NotificationPageDto;
import utp.siga.audit.application.exception.NotificationNotFoundException;
import utp.siga.audit.application.port.in.NotificationUseCase;
import utp.siga.audit.application.query.ListNotificationsQuery;
import utp.siga.audit.domain.model.Notification;
import utp.siga.audit.domain.port.out.EventIdempotencyPort;
import utp.siga.audit.domain.port.out.NotificationRepositoryPort;

@Service
public class NotificationService implements NotificationUseCase {

    public static final String CONSUMER = "notifications-movement-confirmed-v1";
    public static final String TYPE = "MOVEMENT_CONFIRMED";
    public static final String TITLE = "Movimiento confirmado";
    public static final String REFERENCE_TYPE = "Movement";

    private final NotificationRepositoryPort notifications;
    private final EventIdempotencyPort idempotency;
    private final Clock clock;

    public NotificationService(
            NotificationRepositoryPort notifications,
            EventIdempotencyPort idempotency,
            Clock clock) {
        this.notifications = Objects.requireNonNull(notifications, "notifications must not be null");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    @Transactional
    public boolean recordMovementConfirmed(UUID eventId, RecordMovementNotificationCommand command) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        if (!idempotency.claim(eventId, CONSUMER)) {
            return false;
        }
        notifications.save(new Notification(
                UUID.randomUUID(),
                command.recipientUserId(),
                TYPE,
                TITLE,
                "El movimiento %s (%s) fue confirmado."
                        .formatted(command.movementCode(), command.movementType()),
                REFERENCE_TYPE,
                command.movementId(),
                command.occurredAt(),
                null));
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationPageDto list(ListNotificationsQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return notifications.findByRecipient(
                query.recipientUserId(), query.unreadOnly(), query.page(), query.size());
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnread(UUID recipientUserId) {
        return notifications.countUnread(Objects.requireNonNull(recipientUserId, "recipientUserId must not be null"));
    }

    @Override
    @Transactional
    public void markRead(UUID notificationId, UUID recipientUserId) {
        Objects.requireNonNull(notificationId, "notificationId must not be null");
        Objects.requireNonNull(recipientUserId, "recipientUserId must not be null");
        if (notifications.markUnreadAsRead(notificationId, recipientUserId, Instant.now(clock))) {
            return;
        }
        if (!notifications.existsOwned(notificationId, recipientUserId)) {
            throw new NotificationNotFoundException();
        }
    }
}
