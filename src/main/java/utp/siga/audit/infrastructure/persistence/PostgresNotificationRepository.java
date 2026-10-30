package utp.siga.audit.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import utp.siga.audit.application.dto.NotificationDto;
import utp.siga.audit.application.dto.NotificationPageDto;
import utp.siga.audit.domain.model.Notification;
import utp.siga.audit.domain.port.out.NotificationRepositoryPort;

@Repository
public class PostgresNotificationRepository implements NotificationRepositoryPort {

    private static final String SEVERITY_INFO = "INFO";

    private final JdbcTemplate jdbc;

    public PostgresNotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public void save(Notification notification) {
        jdbc.update(
                """
                INSERT INTO audit.notification
                    (id, user_id, type, title, message, reference_type, reference_id, read_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                notification.id(),
                notification.recipientUserId(),
                notification.type(),
                notification.title(),
                notification.message(),
                notification.referenceType(),
                notification.referenceId(),
                toTimestamp(notification.readAt()),
                Timestamp.from(notification.createdAt()));
    }

    @Override
    public NotificationPageDto findByRecipient(
            UUID recipientUserId, boolean unreadOnly, int page, int size) {
        String unreadPredicate = unreadOnly ? " AND read_at IS NULL" : "";
        List<Object> params = new ArrayList<>();
        params.add(recipientUserId);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM audit.notification WHERE user_id = ?" + unreadPredicate,
                Long.class,
                params.toArray());

        String sql = """
                SELECT id, user_id, type, title, message, reference_type, reference_id, read_at, created_at
                FROM audit.notification
                WHERE user_id = ?
                """ + unreadPredicate + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
        params.add(size);
        params.add((long) page * size);
        List<NotificationDto> content = jdbc.query(sql, this::mapRow, params.toArray());
        return new NotificationPageDto(content, page, size, total == null ? 0L : total);
    }

    @Override
    public long countUnread(UUID recipientUserId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM audit.notification WHERE user_id = ? AND read_at IS NULL",
                Long.class,
                recipientUserId);
        return count == null ? 0L : count;
    }

    @Override
    public boolean markUnreadAsRead(UUID id, UUID recipientUserId, Instant readAt) {
        return jdbc.update(
                """
                UPDATE audit.notification
                SET read_at = ?
                WHERE id = ? AND user_id = ? AND read_at IS NULL
                """,
                Timestamp.from(readAt),
                id,
                recipientUserId) == 1;
    }

    @Override
    public boolean existsOwned(UUID id, UUID recipientUserId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM audit.notification WHERE id = ? AND user_id = ?",
                Long.class,
                id,
                recipientUserId);
        return count != null && count == 1L;
    }

    private NotificationDto mapRow(ResultSet rs, int rowNumber) throws SQLException {
        Timestamp readAt = rs.getTimestamp("read_at");
        return new NotificationDto(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("type"),
                rs.getString("title"),
                rs.getString("message"),
                SEVERITY_INFO,
                rs.getString("reference_type"),
                rs.getObject("reference_id", UUID.class),
                readAt == null ? null : readAt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
