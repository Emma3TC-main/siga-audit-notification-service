package utp.siga.audit.presentation.rest;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utp.siga.audit.application.dto.NotificationPageDto;
import utp.siga.audit.application.dto.UnreadNotificationCountDto;
import utp.siga.audit.application.port.in.NotificationUseCase;
import utp.siga.audit.application.query.ListNotificationsQuery;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationUseCase notifications;

    public NotificationController(NotificationUseCase notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public NotificationPageDto list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return notifications.list(new ListNotificationsQuery(subject(jwt), unreadOnly, page, size));
    }

    @GetMapping("/unread-count")
    public UnreadNotificationCountDto unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return new UnreadNotificationCountDto(notifications.countUnread(subject(jwt)));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        notifications.markRead(id, subject(jwt));
        return ResponseEntity.ok().build();
    }

    private static UUID subject(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("JWT sub must be a UUID");
        }
    }
}
