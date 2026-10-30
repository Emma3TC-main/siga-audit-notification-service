package utp.siga.audit.domain.model;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class AuditEvent {

    private final UUID id;
    private final String eventType;
    private final UUID actorId;
    private final String aggregateType;
    private final UUID aggregateId;
    private final String action;
    private final String result;
    private final UUID correlationId;
    private final Map<String, Object> details;
    private final Instant occurredAt;

    public AuditEvent(UUID id, String eventType, UUID actorId, String aggregateType,
            UUID aggregateId, String action, String result, UUID correlationId,
            Map<String, Object> details, Instant occurredAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.actorId = actorId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.result = Objects.requireNonNull(result, "result must not be null");
        this.correlationId = correlationId;
        this.details = details == null ? Collections.emptyMap() : Collections.unmodifiableMap(details);
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    public UUID getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getAction() {
        return action;
    }

    public String getResult() {
        return result;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
