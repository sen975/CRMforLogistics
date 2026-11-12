CREATE TABLE IF NOT EXISTS ai_topic_review_previews (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id uuid NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    source_fingerprint char(64) NOT NULL,
    source_snapshot jsonb NOT NULL,
    assignment_snapshot jsonb NOT NULL DEFAULT '[]'::jsonb,
    expected_versions jsonb NOT NULL DEFAULT '{}'::jsonb,
    from_at timestamptz,
    to_at timestamptz,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    result_topic_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    idempotency_key varchar(100),
    created_by_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    applied_at timestamptz,
    CONSTRAINT ck_ai_topic_review_preview_status CHECK (status IN ('PENDING','APPLIED','EXPIRED'))
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ai_topic_review_preview_idempotency
    ON ai_topic_review_previews(created_by_user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_ai_topic_review_preview_contact
    ON ai_topic_review_previews(contact_id, created_at DESC);
