package utp.siga.audit.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import utp.siga.audit.application.dto.AuditEventDto;
import utp.siga.audit.application.dto.AuditPageDto;
import utp.siga.audit.application.query.SearchAuditEventsQuery;
import utp.siga.audit.domain.model.AuditEvent;
import utp.siga.audit.domain.port.out.AuditEventRepositoryPort;

@Repository
public class PostgresAuditEventRepository implements AuditEventRepositoryPort {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PostgresAuditEventRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public void save(AuditEvent event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event.getDetails());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize audit event details to JSON", e);
        }

        jdbc.update("""
                INSERT INTO audit.audit_event
                    (id, event_type, actor_id, aggregate_type, aggregate_id, action, result, correlation_id, details, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                event.getId(),
                event.getEventType(),
                event.getActorId(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getAction(),
                event.getResult(),
                event.getCorrelationId(),
                json,
                Timestamp.from(event.getOccurredAt()));
    }

    @Override
    public AuditPageDto findByFilters(SearchAuditEventsQuery query) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (query.actorId() != null) {
            where.append(" AND actor_id = ?");
            params.add(query.actorId());
        }
        if (query.aggregateType() != null && !query.aggregateType().isBlank()) {
            where.append(" AND aggregate_type = ?");
            params.add(query.aggregateType().trim());
        }
        if (query.aggregateId() != null) {
            where.append(" AND aggregate_id = ?");
            params.add(query.aggregateId());
        }
        if (query.correlationId() != null) {
            where.append(" AND correlation_id = ?");
            params.add(query.correlationId());
        }
        if (query.from() != null) {
            where.append(" AND occurred_at >= ?");
            params.add(Timestamp.from(query.from()));
        }
        if (query.to() != null) {
            where.append(" AND occurred_at <= ?");
            params.add(Timestamp.from(query.to()));
        }

        String countSql = "SELECT count(*) FROM audit.audit_event" + where;
        Long total = jdbc.queryForObject(countSql, Long.class, params.toArray());
        long totalElements = total != null ? total : 0L;

        String selectSql = """
                SELECT id, event_type, actor_id, aggregate_type, aggregate_id, action, result, correlation_id, details::text, occurred_at
                FROM audit.audit_event
                """ + where + " ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?";

        List<Object> selectParams = new ArrayList<>(params);
        selectParams.add(query.size());
        selectParams.add((long) query.page() * query.size());

        List<AuditEventDto> content = jdbc.query(selectSql, (rs, rowNum) -> mapRow(rs), selectParams.toArray());

        return new AuditPageDto(content, query.page(), query.size(), totalElements);
    }

    private AuditEventDto mapRow(ResultSet rs) throws SQLException {
        UUID id = (UUID) rs.getObject("id");
        String eventType = rs.getString("event_type");
        UUID actorId = (UUID) rs.getObject("actor_id");
        String aggregateType = rs.getString("aggregate_type");
        UUID aggregateId = (UUID) rs.getObject("aggregate_id");
        String action = rs.getString("action");
        String result = rs.getString("result");
        UUID correlationId = (UUID) rs.getObject("correlation_id");
        String detailsRaw = rs.getString("details");
        Timestamp occurredAtTs = rs.getTimestamp("occurred_at");
        Instant occurredAt = occurredAtTs != null ? occurredAtTs.toInstant() : null;

        Map<String, Object> details;
        if (detailsRaw != null && !detailsRaw.isBlank()) {
            try {
                details = objectMapper.readValue(detailsRaw, MAP_TYPE);
            } catch (JsonProcessingException e) {
                details = Collections.emptyMap();
            }
        } else {
            details = Collections.emptyMap();
        }

        return new AuditEventDto(
                id,
                eventType,
                actorId,
                aggregateType,
                aggregateId,
                action,
                result,
                correlationId,
                details,
                occurredAt);
    }
}
