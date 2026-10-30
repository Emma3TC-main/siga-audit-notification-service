CREATE TABLE audit.audit_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type varchar(120) NOT NULL,
    actor_id uuid,
    aggregate_type varchar(80),
    aggregate_id uuid,
    action varchar(120) NOT NULL,
    result varchar(30) NOT NULL,
    correlation_id uuid,
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_occurred ON audit.audit_event(occurred_at DESC);
CREATE INDEX idx_audit_correlation ON audit.audit_event(correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX idx_audit_aggregate ON audit.audit_event(aggregate_type, aggregate_id, occurred_at DESC);
CREATE INDEX idx_audit_actor ON audit.audit_event(actor_id, occurred_at DESC) WHERE actor_id IS NOT NULL;

CREATE TABLE audit.notification (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    type varchar(80) NOT NULL,
    title varchar(160) NOT NULL,
    message text NOT NULL,
    reference_type varchar(80),
    reference_id uuid,
    read_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_user_unread ON audit.notification(user_id, created_at DESC) WHERE read_at IS NULL;
CREATE INDEX idx_notification_user_created ON audit.notification(user_id, created_at DESC);

CREATE TABLE audit.processed_event (
    event_id uuid NOT NULL,
    consumer varchar(120) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);
