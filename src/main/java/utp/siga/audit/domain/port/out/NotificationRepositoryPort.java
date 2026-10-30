package utp.siga.audit.domain.port.out;

import java.time.Instant;
import java.util.UUID;
import utp.siga.audit.application.dto.NotificationPageDto;
import utp.siga.audit.domain.model.Notification;

public interface NotificationRepositoryPort {

    void save(Notification notification);

    NotificationPageDto findByRecipient(UUID recipientUserId, boolean unreadOnly, int page, int size);

    long countUnread(UUID recipientUserId);

    boolean markUnreadAsRead(UUID id, UUID recipientUserId, Instant readAt);

    boolean existsOwned(UUID id, UUID recipientUserId);
}
