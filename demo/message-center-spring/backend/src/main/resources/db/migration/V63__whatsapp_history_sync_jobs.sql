CREATE TABLE whatsapp_history_sync_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_account_id uuid NOT NULL REFERENCES channel_accounts(id) ON DELETE CASCADE,
    owner_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    error_code varchar(96),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_owner varchar(128),
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT ck_whatsapp_history_sync_job_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_whatsapp_history_sync_job_attempt_count
        CHECK (attempt_count BETWEEN 0 AND 3)
);

CREATE UNIQUE INDEX ux_whatsapp_history_sync_job_active_account
    ON whatsapp_history_sync_jobs(channel_account_id)
    WHERE status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT');

CREATE INDEX ix_whatsapp_history_sync_job_runnable
    ON whatsapp_history_sync_jobs(status, next_attempt_at, created_at);

CREATE INDEX ix_whatsapp_history_sync_job_owner_account
    ON whatsapp_history_sync_jobs(owner_user_id, channel_account_id, created_at DESC);
