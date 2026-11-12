CREATE TABLE IF NOT EXISTS ai_topic_fusion_previews (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type varchar(20) NOT NULL,
    owner_id uuid NOT NULL,
    topic_ids jsonb NOT NULL,
    result_snapshot jsonb NOT NULL,
    expected_versions jsonb NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    result_topic_id uuid REFERENCES ai_topics(id) ON DELETE SET NULL,
    idempotency_key varchar(100),
    created_by_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    applied_at timestamptz,
    CONSTRAINT ck_ai_topic_fusion_preview_owner CHECK (owner_type IN ('CONTACT','WECOM_GROUP')),
    CONSTRAINT ck_ai_topic_fusion_preview_status CHECK (status IN ('PENDING','APPLIED','EXPIRED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_ai_topic_fusion_preview_idempotency
    ON ai_topic_fusion_previews(created_by_user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_ai_topic_fusion_preview_owner
    ON ai_topic_fusion_previews(owner_type, owner_id, created_at DESC);
