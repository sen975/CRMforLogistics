ALTER TABLE wecom_source_conversations
    ADD COLUMN name_resolution_status varchar(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN last_name_checked_at timestamptz,
    ADD COLUMN name_next_retry_at timestamptz,
    ADD COLUMN name_error_code varchar(100);

ALTER TABLE wecom_source_conversations
    ADD CONSTRAINT ck_wecom_source_conversation_name_resolution_status
    CHECK (name_resolution_status IN ('PENDING', 'RESOLVED', 'RETRY_WAIT', 'UNAVAILABLE'));

CREATE TABLE wecom_group_name_refresh_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_conversation_id uuid NOT NULL REFERENCES wecom_source_conversations(id) ON DELETE CASCADE,
    trigger_source varchar(20) NOT NULL,
    requested_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    lease_owner varchar(100),
    lease_until timestamptz,
    error_code varchar(100),
    error_diagnostic varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT ck_wecom_group_name_refresh_trigger
        CHECK (trigger_source IN ('MANUAL', 'TOPIC_UPDATED')),
    CONSTRAINT ck_wecom_group_name_refresh_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'COMPLETED', 'UNAVAILABLE', 'FAILED')),
    CONSTRAINT ck_wecom_group_name_refresh_attempt_count
        CHECK (attempt_count >= 0 AND attempt_count <= 10),
    CONSTRAINT ck_wecom_group_name_refresh_manual_actor
        CHECK (trigger_source <> 'MANUAL' OR requested_by_user_id IS NOT NULL)
);

CREATE UNIQUE INDEX ux_wecom_group_name_refresh_active
    ON wecom_group_name_refresh_jobs(source_conversation_id)
    WHERE status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT');

CREATE INDEX ix_wecom_group_name_refresh_runnable
    ON wecom_group_name_refresh_jobs(next_attempt_at, created_at, id)
    WHERE status IN ('PENDING', 'RETRY_WAIT');
