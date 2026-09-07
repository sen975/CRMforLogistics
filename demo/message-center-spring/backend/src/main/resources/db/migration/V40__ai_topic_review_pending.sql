ALTER TABLE ai_topics
    DROP CONSTRAINT IF EXISTS ck_ai_topics_status;

ALTER TABLE ai_topics
    ADD CONSTRAINT ck_ai_topics_status
    CHECK (status IN ('READY', 'REVIEW_PENDING', 'STORED', 'ARCHIVED'));

ALTER TABLE ai_topics
    ADD COLUMN IF NOT EXISTS review_origin varchar(30),
    ADD COLUMN IF NOT EXISTS review_source_contact_id uuid REFERENCES contacts(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS review_source_topic_id uuid REFERENCES ai_topics(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS review_operation_id uuid;

ALTER TABLE ai_topics
    DROP CONSTRAINT IF EXISTS ck_ai_topics_review_origin;

ALTER TABLE ai_topics
    ADD CONSTRAINT ck_ai_topics_review_origin
    CHECK (review_origin IS NULL OR review_origin IN ('MERGE_SOURCE', 'SPLIT_SOURCE', 'MANUAL_SELECTION'));

CREATE INDEX IF NOT EXISTS ix_ai_topics_review_owner
    ON ai_topics(owner_type, owner_id, status, updated_at DESC)
    WHERE status = 'REVIEW_PENDING';
