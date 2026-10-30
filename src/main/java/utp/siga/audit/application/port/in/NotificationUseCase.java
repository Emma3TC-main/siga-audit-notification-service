package utp.siga.audit.application.port.in;

import java.util.UUID;
import utp.siga.audit.application.command.RecordMovementNotificationCommand;
import utp.siga.audit.application.dto.NotificationPageDto;
import utp.siga.audit.application.query.ListNotificationsQuery;

public interface NotificationUseCase {

    boolean recordMovementConfirmed(UUID eventId, RecordMovementNotificationCommand command);

    NotificationPageDto list(ListNotificationsQuery query);

    long countUnread(UUID recipientUserId);

    void markRead(UUID notificationId, UUID recipientUserId);
}
