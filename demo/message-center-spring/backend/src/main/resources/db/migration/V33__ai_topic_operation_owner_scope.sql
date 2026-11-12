-- Operation jobs need the same owner boundary as topics so group approvals can run asynchronously.
ALTER TABLE ai_topic_operation_jobs
    ADD COLUMN IF NOT EXISTS owner_type varchar(20),
    ADD COLUMN IF NOT EXISTS owner_id uuid,
    ADD COLUMN IF NOT EXISTS wecom_group_source_conversation_id uuid
        REFERENCES wecom_source_conversations(id) ON DELETE CASCADE;

UPDATE ai_topic_operation_jobs
SET owner_type = 'CONTACT', owner_id = contact_id
WHERE owner_type IS NULL;

ALTER TABLE ai_topic_operation_jobs ALTER COLUMN contact_id DROP NOT NULL;
ALTER TABLE ai_topic_operation_jobs ALTER COLUMN owner_type SET NOT NULL;
ALTER TABLE ai_topic_operation_jobs ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE ai_topic_operation_jobs DROP CONSTRAINT IF EXISTS ck_ai_topic_operation_owner_type;
ALTER TABLE ai_topic_operation_jobs ADD CONSTRAINT ck_ai_topic_operation_owner_type
    CHECK (owner_type IN ('CONTACT', 'WECOM_GROUP'));
ALTER TABLE ai_topic_operation_jobs DROP CONSTRAINT IF EXISTS ck_ai_topic_operation_owner;
ALTER TABLE ai_topic_operation_jobs ADD CONSTRAINT ck_ai_topic_operation_owner
    CHECK ((owner_type = 'CONTACT' AND contact_id = owner_id
            AND wecom_group_source_conversation_id IS NULL)
        OR (owner_type = 'WECOM_GROUP' AND contact_id IS NULL
            AND wecom_group_source_conversation_id = owner_id));

CREATE INDEX IF NOT EXISTS ix_ai_topic_operation_owner_target
    ON ai_topic_operation_jobs(owner_type, owner_id, operation_kind, status, created_at DESC);

DROP INDEX IF EXISTS ux_ai_topic_inbox_pending;
CREATE UNIQUE INDEX IF NOT EXISTS ux_ai_topic_inbox_active
    ON ai_topic_inbox_requests(topic_id) WHERE status IN ('PENDING', 'APPROVED');
