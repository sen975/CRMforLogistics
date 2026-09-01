-- Mixed CONTACT/WECOM_GROUP Topic ownership and persistent store workflow.
ALTER TABLE ai_topics
    ADD COLUMN IF NOT EXISTS owner_type varchar(20),
    ADD COLUMN IF NOT EXISTS owner_id uuid,
    ADD COLUMN IF NOT EXISTS wecom_group_source_conversation_id uuid
        REFERENCES wecom_source_conversations(id) ON DELETE CASCADE;

UPDATE ai_topics
SET owner_type = 'CONTACT', owner_id = contact_id
WHERE owner_type IS NULL;

ALTER TABLE ai_topics ALTER COLUMN contact_id DROP NOT NULL;
ALTER TABLE ai_topics ALTER COLUMN owner_type SET NOT NULL;
ALTER TABLE ai_topics ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE ai_topics DROP CONSTRAINT IF EXISTS ck_ai_topics_status;
ALTER TABLE ai_topics ADD CONSTRAINT ck_ai_topics_status
    CHECK (status IN ('READY', 'STORED', 'ARCHIVED'));
ALTER TABLE ai_topics DROP CONSTRAINT IF EXISTS ck_ai_topics_owner_type;
ALTER TABLE ai_topics ADD CONSTRAINT ck_ai_topics_owner_type
    CHECK (owner_type IN ('CONTACT', 'WECOM_GROUP'));
ALTER TABLE ai_topics DROP CONSTRAINT IF EXISTS ck_ai_topics_owner_contact;
ALTER TABLE ai_topics ADD CONSTRAINT ck_ai_topics_owner_contact
    CHECK ((owner_type = 'CONTACT' AND contact_id = owner_id AND wecom_group_source_conversation_id IS NULL)
        OR (owner_type = 'WECOM_GROUP' AND contact_id IS NULL
            AND wecom_group_source_conversation_id = owner_id));

UPDATE ai_topics SET status = 'STORED' WHERE status = 'DISCARDED';

CREATE INDEX IF NOT EXISTS ix_ai_topics_owner_time
    ON ai_topics(owner_type, owner_id, last_occurred_at DESC, id);

ALTER TABLE ai_topic_items
    ADD COLUMN IF NOT EXISTS wecom_message_summary_job_id uuid
        REFERENCES wecom_message_summary_jobs(id) ON DELETE RESTRICT;
ALTER TABLE ai_topic_items DROP CONSTRAINT IF EXISTS ck_ai_topic_items_source;
ALTER TABLE ai_topic_items ADD CONSTRAINT ck_ai_topic_items_source
    CHECK (((message_id IS NOT NULL)::int + (call_record_id IS NOT NULL)::int
        + (wecom_message_summary_job_id IS NOT NULL)::int) = 1);
ALTER TABLE ai_topic_items DROP CONSTRAINT IF EXISTS ck_ai_topic_items_channel;
ALTER TABLE ai_topic_items ADD CONSTRAINT ck_ai_topic_items_channel
    CHECK (channel_type IN ('chatapp', 'email', 'phone', 'wecom'));
CREATE UNIQUE INDEX IF NOT EXISTS ux_ai_topic_items_wecom_summary
    ON ai_topic_items(wecom_message_summary_job_id)
    WHERE wecom_message_summary_job_id IS NOT NULL;

ALTER TABLE ai_topic_generation_jobs
    ADD COLUMN IF NOT EXISTS owner_type varchar(20),
    ADD COLUMN IF NOT EXISTS owner_id uuid,
    ADD COLUMN IF NOT EXISTS wecom_group_source_conversation_id uuid
        REFERENCES wecom_source_conversations(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS trigger_source varchar(20) NOT NULL DEFAULT 'MANUAL';
UPDATE ai_topic_generation_jobs
SET owner_type = 'CONTACT', owner_id = contact_id
WHERE owner_type IS NULL;
ALTER TABLE ai_topic_generation_jobs ALTER COLUMN contact_id DROP NOT NULL;
ALTER TABLE ai_topic_generation_jobs ALTER COLUMN owner_type SET NOT NULL;
ALTER TABLE ai_topic_generation_jobs ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE ai_topic_generation_jobs DROP CONSTRAINT IF EXISTS ck_ai_topic_generation_owner_type;
ALTER TABLE ai_topic_generation_jobs ADD CONSTRAINT ck_ai_topic_generation_owner_type
    CHECK (owner_type IN ('CONTACT', 'WECOM_GROUP'));
ALTER TABLE ai_topic_generation_jobs DROP CONSTRAINT IF EXISTS ck_ai_topic_generation_owner;
ALTER TABLE ai_topic_generation_jobs ADD CONSTRAINT ck_ai_topic_generation_owner
    CHECK ((owner_type = 'CONTACT' AND contact_id = owner_id AND wecom_group_source_conversation_id IS NULL)
        OR (owner_type = 'WECOM_GROUP' AND contact_id IS NULL
            AND wecom_group_source_conversation_id = owner_id));
ALTER TABLE ai_topic_generation_jobs DROP CONSTRAINT IF EXISTS ck_ai_topic_generation_trigger;
ALTER TABLE ai_topic_generation_jobs ADD CONSTRAINT ck_ai_topic_generation_trigger
    CHECK (trigger_source IN ('AUTO', 'MANUAL'));
CREATE INDEX IF NOT EXISTS ix_ai_topic_generation_owner_runnable
    ON ai_topic_generation_jobs(owner_type, owner_id, status, next_attempt_at);

ALTER TABLE ai_topic_versions
    DROP CONSTRAINT IF EXISTS ck_ai_topic_versions_change;
UPDATE ai_topic_versions SET change_type = 'STORED' WHERE change_type = 'DISCARDED';
ALTER TABLE ai_topic_versions ADD CONSTRAINT ck_ai_topic_versions_change
    CHECK (change_type IN ('AI_GENERATED', 'EMPLOYEE_EDITED', 'MERGED', 'STORED', 'RESTORED'));

ALTER TABLE ai_topic_operation_jobs
    DROP CONSTRAINT IF EXISTS ck_ai_topic_operation_kind;
UPDATE ai_topic_operation_jobs SET operation_kind = 'STORE' WHERE operation_kind = 'DISCARD';
ALTER TABLE ai_topic_operation_jobs ADD CONSTRAINT ck_ai_topic_operation_kind
    CHECK (operation_kind IN ('EDIT', 'MERGE', 'STORE', 'RESTORE'));

ALTER TABLE ai_topic_generation_attempts
    ADD COLUMN IF NOT EXISTS owner_type varchar(20),
    ADD COLUMN IF NOT EXISTS owner_id uuid;
UPDATE ai_topic_generation_attempts a
SET owner_type = j.owner_type, owner_id = j.owner_id
FROM ai_topic_generation_jobs j
WHERE a.generation_job_id = j.id AND a.owner_type IS NULL;
ALTER TABLE ai_topic_generation_attempts ALTER COLUMN contact_id DROP NOT NULL;
ALTER TABLE ai_topic_generation_attempts ALTER COLUMN owner_type SET NOT NULL;
ALTER TABLE ai_topic_generation_attempts ALTER COLUMN owner_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS ix_ai_topic_attempt_owner_time
    ON ai_topic_generation_attempts(owner_type, owner_id, created_at DESC);

CREATE TABLE IF NOT EXISTS ai_topic_owner_activity (
    owner_type varchar(20) NOT NULL,
    owner_id uuid NOT NULL,
    latest_event_at timestamptz NOT NULL,
    quiet_deadline timestamptz NOT NULL,
    activity_version bigint NOT NULL DEFAULT 1,
    status varchar(20) NOT NULL DEFAULT 'WAITING',
    lease_owner varchar(100),
    lease_until timestamptz,
    linked_generation_job_id uuid REFERENCES ai_topic_generation_jobs(id) ON DELETE SET NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_type, owner_id),
    CONSTRAINT ck_ai_topic_owner_activity_type CHECK (owner_type IN ('CONTACT', 'WECOM_GROUP')),
    CONSTRAINT ck_ai_topic_owner_activity_status CHECK (status IN ('WAITING', 'LEASED', 'GENERATING')),
    CONSTRAINT ck_ai_topic_owner_activity_version CHECK (activity_version > 0)
);
CREATE INDEX IF NOT EXISTS ix_ai_topic_owner_activity_due
    ON ai_topic_owner_activity(status, quiet_deadline, lease_until);

CREATE TABLE IF NOT EXISTS ai_topic_inbox_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    topic_id uuid NOT NULL REFERENCES ai_topics(id) ON DELETE CASCADE,
    requested_by_user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    reviewed_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    reason varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    reviewed_at timestamptz,
    CONSTRAINT ck_ai_topic_inbox_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ai_topic_inbox_pending
    ON ai_topic_inbox_requests(topic_id) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS ix_ai_topic_inbox_status_time
    ON ai_topic_inbox_requests(status, created_at DESC);
