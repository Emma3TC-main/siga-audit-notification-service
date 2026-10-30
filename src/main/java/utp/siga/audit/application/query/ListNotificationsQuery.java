package utp.siga.audit.application.query;

import java.util.Objects;
import java.util.UUID;

public record ListNotificationsQuery(UUID recipientUserId, boolean unreadOnly, int page, int size) {

    public ListNotificationsQuery {
        Objects.requireNonNull(recipientUserId, "recipientUserId must not be null");
        if (page < 0) {
            throw new IllegalArgumentException("page must be greater than or equal to 0");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
    }
}
