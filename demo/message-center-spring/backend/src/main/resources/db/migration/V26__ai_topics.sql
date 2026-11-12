CREATE TABLE ai_topics (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id uuid NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    created_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    title varchar(200) NOT NULL,
    ai_summary text NOT NULL,
    confirmed_summary text,
    status varchar(20) NOT NULL DEFAULT 'READY',
    first_occurred_at timestamptz NOT NULL,
    last_occurred_at timestamptz NOT NULL,
    input_fingerprint char(64) NOT NULL,
    version bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_topics_status CHECK (status IN ('READY', 'ARCHIVED')),
    CONSTRAINT ck_ai_topics_title CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_ai_topics_summary CHECK (length(btrim(ai_summary)) > 0)
);

CREATE INDEX ix_ai_topics_contact_time ON ai_topics(contact_id, last_occurred_at DESC, id);

CREATE TABLE ai_topic_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    topic_id uuid NOT NULL REFERENCES ai_topics(id) ON DELETE CASCADE,
    message_id uuid REFERENCES messages(id) ON DELETE CASCADE,
    call_record_id uuid REFERENCES call_records(id) ON DELETE CASCADE,
    occurred_at timestamptz NOT NULL,
    channel_type varchar(20) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_topic_items_source CHECK ((message_id IS NOT NULL) <> (call_record_id IS NOT NULL)),
    CONSTRAINT ck_ai_topic_items_channel CHECK (channel_type IN ('chatapp', 'email', 'phone'))
);

CREATE UNIQUE INDEX ux_ai_topic_items_message ON ai_topic_items(message_id) WHERE message_id IS NOT NULL;
CREATE UNIQUE INDEX ux_ai_topic_items_call ON ai_topic_items(call_record_id) WHERE call_record_id IS NOT NULL;
CREATE INDEX ix_ai_topic_items_topic_time ON ai_topic_items(topic_id, occurred_at, id);

CREATE TABLE ai_topic_generation_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id uuid NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    job_kind varchar(20) NOT NULL,
    input_fingerprint char(64) NOT NULL,
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
    CONSTRAINT uq_ai_topic_generation_input UNIQUE (contact_id, input_fingerprint),
    CONSTRAINT ck_ai_topic_generation_kind CHECK (job_kind IN ('INITIAL', 'INCREMENTAL')),
    CONSTRAINT ck_ai_topic_generation_status CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_ai_topic_generation_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX ix_ai_topic_generation_runnable
    ON ai_topic_generation_jobs(status, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'RETRY_WAIT');

CREATE TABLE ai_topic_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    topic_id uuid NOT NULL REFERENCES ai_topics(id) ON DELETE CASCADE,
    version bigint NOT NULL,
    change_type varchar(30) NOT NULL,
    title varchar(200) NOT NULL,
    summary text NOT NULL,
    source_topic_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    actor_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_topic_versions_change CHECK (change_type IN ('AI_GENERATED', 'EMPLOYEE_EDITED', 'MERGED')),
    UNIQUE (topic_id, version)
);
