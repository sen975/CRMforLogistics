CREATE INDEX IF NOT EXISTS ix_wecom_viewer_audit_retention
    ON wecom_viewer_audit (occurred_at, id);

CREATE INDEX IF NOT EXISTS ix_wecom_authorization_audit_retention
    ON wecom_authorization_audit (occurred_at, id);

CREATE TABLE wecom_audit_retention_state (
    stream varchar(32) PRIMARY KEY,
    last_started_at timestamptz,
    last_completed_at timestamptz,
    deleted_count bigint NOT NULL DEFAULT 0,
    status varchar(32) NOT NULL,
    error_code varchar(128),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_wecom_audit_retention_stream
        CHECK (stream IN ('viewer', 'authorization')),
    CONSTRAINT ck_wecom_audit_retention_status
        CHECK (status IN ('running', 'success', 'skipped_locked', 'budget_remaining', 'failed')),
    CONSTRAINT ck_wecom_audit_retention_deleted_count
        CHECK (deleted_count >= 0)
);
