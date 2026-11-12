ALTER TABLE ai_topics
    DROP CONSTRAINT ck_ai_topics_status;

ALTER TABLE ai_topics
    ADD CONSTRAINT ck_ai_topics_status
    CHECK (status IN ('READY', 'ARCHIVED', 'DISCARDED'));

ALTER TABLE ai_topic_versions
    DROP CONSTRAINT ck_ai_topic_versions_change;

ALTER TABLE ai_topic_versions
    ADD CONSTRAINT ck_ai_topic_versions_change
    CHECK (change_type IN ('AI_GENERATED', 'EMPLOYEE_EDITED', 'MERGED', 'DISCARDED', 'RESTORED'));

CREATE TABLE ai_topic_operation_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id uuid NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    created_by_user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    operation_kind varchar(20) NOT NULL,
    request_payload jsonb NOT NULL,
    expected_versions jsonb NOT NULL DEFAULT '{}'::jsonb,
    idempotency_key varchar(100) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_until timestamptz,
    lease_owner varchar(100),
    last_error_code varchar(100),
    last_error_message varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT uq_ai_topic_operation_actor_key UNIQUE (created_by_user_id, idempotency_key),
    CONSTRAINT ck_ai_topic_operation_kind CHECK (operation_kind IN ('EDIT', 'MERGE', 'DISCARD', 'RESTORE')),
    CONSTRAINT ck_ai_topic_operation_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_ai_topic_operation_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX ix_ai_topic_operation_runnable
    ON ai_topic_operation_jobs(status, next_attempt_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX ix_ai_topic_operation_target
    ON ai_topic_operation_jobs(contact_id, operation_kind, status, created_at DESC);
