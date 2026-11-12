CREATE TABLE channel_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_account_id uuid NOT NULL REFERENCES channel_accounts (id),
    provider_event_id varchar(255),
    event_type varchar(100) NOT NULL,
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    payload_jsonb jsonb NOT NULL,
    payload_hash char(64) NOT NULL,
    processing_status varchar(20) NOT NULL DEFAULT 'received',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_owner varchar(100),
    lease_until timestamptz,
    last_error_code varchar(100),
    last_error_message text,
    processed_at timestamptz,
    trace_id varchar(100),
    CONSTRAINT ck_channel_events_status
        CHECK (processing_status IN ('received', 'processing', 'processed', 'retry_wait', 'dead')),
    CONSTRAINT ck_channel_events_attempt_count CHECK (attempt_count >= 0)
);

CREATE UNIQUE INDEX ux_channel_events_provider_id
    ON channel_events (channel_account_id, provider_event_id)
    WHERE provider_event_id IS NOT NULL;

CREATE UNIQUE INDEX ux_channel_events_payload
    ON channel_events (channel_account_id, event_type, payload_hash)
    WHERE provider_event_id IS NULL;

ALTER TABLE messages
    ADD CONSTRAINT fk_messages_source_event
    FOREIGN KEY (source_event_id) REFERENCES channel_events (id);

CREATE TABLE outbox_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id uuid NOT NULL UNIQUE REFERENCES messages (id),
    job_type varchar(50) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'pending',
    attempt_count integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_owner varchar(100),
    lease_until timestamptz,
    last_error_code varchar(100),
    last_error_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT ck_outbox_jobs_status
        CHECK (status IN ('pending', 'processing', 'retry_wait', 'completed', 'dead')),
    CONSTRAINT ck_outbox_jobs_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_outbox_jobs_max_attempts CHECK (max_attempts > 0),
    CONSTRAINT ck_outbox_jobs_attempt_limit CHECK (attempt_count <= max_attempts)
);

CREATE TABLE attachments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id uuid NOT NULL REFERENCES messages (id),
    storage_provider varchar(20) NOT NULL DEFAULT 'minio',
    bucket varchar(100) NOT NULL,
    object_key varchar(1024) NOT NULL,
    original_name varchar(500) NOT NULL,
    mime_type varchar(255) NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 char(64) NOT NULL,
    media_kind varchar(30) NOT NULL,
    width integer,
    height integer,
    duration_ms bigint,
    storage_status varchar(20) NOT NULL DEFAULT 'pending',
    failure_code varchar(100),
    failure_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    ready_at timestamptz,
    deleted_at timestamptz,
    CONSTRAINT uq_attachment_object UNIQUE (bucket, object_key),
    CONSTRAINT ck_attachments_provider CHECK (storage_provider = 'minio'),
    CONSTRAINT ck_attachments_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_attachments_dimensions CHECK (
        (width IS NULL OR width > 0)
        AND (height IS NULL OR height > 0)
        AND (duration_ms IS NULL OR duration_ms >= 0)
    ),
    CONSTRAINT ck_attachments_media_kind
        CHECK (media_kind IN ('image', 'video', 'audio', 'document', 'archive', 'other')),
    CONSTRAINT ck_attachments_storage_status
        CHECK (storage_status IN ('pending', 'ready', 'failed', 'deleted'))
);

CREATE TABLE audit_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id uuid REFERENCES users (id) ON DELETE SET NULL,
    action varchar(100) NOT NULL,
    resource_type varchar(100) NOT NULL,
    resource_id uuid,
    before_summary_jsonb jsonb,
    after_summary_jsonb jsonb,
    result varchar(30) NOT NULL,
    ip_address inet,
    user_agent varchar(500),
    trace_id varchar(100),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_audit_logs_result CHECK (result IN ('success', 'denied', 'failed'))
);

CREATE TABLE data_import_batches (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_type varchar(50) NOT NULL,
    source_identifier text NOT NULL,
    source_hash char(64) NOT NULL,
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    source_count integer NOT NULL DEFAULT 0,
    inserted_count integer NOT NULL DEFAULT 0,
    updated_count integer NOT NULL DEFAULT 0,
    skipped_count integer NOT NULL DEFAULT 0,
    failed_count integer NOT NULL DEFAULT 0,
    status varchar(20) NOT NULL DEFAULT 'running',
    executed_by uuid REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT uq_data_import_batch_source
        UNIQUE (batch_type, source_identifier, source_hash),
    CONSTRAINT ck_data_import_batches_status CHECK (status IN ('running', 'completed', 'failed')),
    CONSTRAINT ck_data_import_batches_counts CHECK (
        source_count >= 0
        AND inserted_count >= 0
        AND updated_count >= 0
        AND skipped_count >= 0
        AND failed_count >= 0
    )
);

CREATE TABLE data_import_errors (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id uuid NOT NULL REFERENCES data_import_batches (id),
    source_line bigint,
    record_identifier varchar(255),
    error_code varchar(100) NOT NULL,
    error_message text NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_data_import_errors_source_line CHECK (source_line IS NULL OR source_line > 0)
);

CREATE INDEX ix_channel_events_claim
    ON channel_events (processing_status, next_attempt_at, received_at)
    WHERE processing_status IN ('received', 'retry_wait');

CREATE INDEX ix_outbox_jobs_claim
    ON outbox_jobs (status, next_attempt_at, created_at)
    WHERE status IN ('pending', 'retry_wait');

CREATE INDEX ix_messages_unread
    ON messages (conversation_id, ingest_sequence)
    WHERE direction = 'inbound' AND counts_as_unread;

CREATE INDEX ix_conversations_last_message
    ON conversations (last_message_at DESC, id);

CREATE INDEX ix_messages_conversation_display
    ON messages (conversation_id, occurred_at, id);

CREATE INDEX ix_attachments_sha256 ON attachments (sha256);
CREATE INDEX ix_attachments_message ON attachments (message_id);
CREATE INDEX ix_audit_logs_actor_time ON audit_logs (actor_user_id, occurred_at DESC);
CREATE INDEX ix_audit_logs_resource_time ON audit_logs (resource_type, resource_id, occurred_at DESC);
CREATE INDEX ix_data_import_errors_batch ON data_import_errors (batch_id, source_line);

CREATE INDEX ix_companies_name_trgm ON companies USING gin (name gin_trgm_ops);
CREATE INDEX ix_contacts_display_name_trgm ON contacts USING gin (display_name gin_trgm_ops);
CREATE INDEX ix_contacts_remark_trgm ON contacts USING gin (remark gin_trgm_ops);
CREATE INDEX ix_contact_identities_value_trgm ON contact_identities USING gin (identity_value gin_trgm_ops);
CREATE INDEX ix_messages_body_text_trgm ON messages USING gin (body_text gin_trgm_ops);
