CREATE TABLE contact_memory_trigger_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id uuid NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    contact_id uuid NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    owner_user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    ingest_sequence bigint NOT NULL,
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_owner varchar(100),
    lease_token uuid,
    lease_acquired_at timestamptz,
    lease_expires_at timestamptz,
    last_failure_code varchar(100),
    last_failure_message varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    applied_at timestamptz,
    CONSTRAINT ux_contact_memory_trigger_event_message UNIQUE (message_id),
    CONSTRAINT ck_contact_memory_trigger_event_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'APPLIED', 'FAILED')
    ),
    CONSTRAINT ck_contact_memory_trigger_event_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_contact_memory_trigger_event_lease_order CHECK (
        lease_expires_at IS NULL OR lease_acquired_at IS NULL
        OR lease_expires_at > lease_acquired_at
    )
);

CREATE INDEX ix_contact_memory_trigger_events_due
    ON contact_memory_trigger_events (status, next_attempt_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX ix_contact_memory_trigger_events_expired
    ON contact_memory_trigger_events (lease_expires_at, created_at)
    WHERE status = 'PROCESSING';

CREATE INDEX ix_contact_memory_trigger_events_owner_contact
    ON contact_memory_trigger_events (owner_user_id, contact_id, received_at);
