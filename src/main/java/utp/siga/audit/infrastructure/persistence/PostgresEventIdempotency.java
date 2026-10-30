package utp.siga.audit.infrastructure.persistence;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import utp.siga.audit.domain.port.out.EventIdempotencyPort;

@Repository
public class PostgresEventIdempotency implements EventIdempotencyPort {

    private final JdbcTemplate jdbc;

    public PostgresEventIdempotency(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public boolean claim(UUID eventId, String consumer) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(consumer, "consumer must not be null");
        return jdbc.update(
                """
                INSERT INTO audit.processed_event (event_id, consumer, processed_at)
                VALUES (?, ?, now())
                ON CONFLICT (event_id, consumer) DO NOTHING
                """,
                eventId,
                consumer) == 1;
    }

    @Override
    public boolean isProcessed(UUID eventId, String consumer) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(consumer, "consumer must not be null");
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM audit.processed_event WHERE event_id = ? AND consumer = ?",
                Long.class,
                eventId,
                consumer);
        return count != null && count > 0;
    }

    @Override
    public void recordProcessed(UUID eventId, String consumer) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(consumer, "consumer must not be null");
        jdbc.update(
                "INSERT INTO audit.processed_event (event_id, consumer, processed_at) VALUES (?, ?, now())",
                eventId,
                consumer);
    }
}
