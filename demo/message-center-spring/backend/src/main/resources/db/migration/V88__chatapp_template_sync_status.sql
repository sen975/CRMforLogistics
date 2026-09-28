ALTER TABLE channel_accounts
    ADD COLUMN IF NOT EXISTS template_sync_status varchar(20) NOT NULL DEFAULT 'NEVER_SYNCED',
    ADD COLUMN IF NOT EXISTS template_last_synced_at timestamptz,
    ADD COLUMN IF NOT EXISTS template_last_error_code varchar(128);

ALTER TABLE channel_accounts
    DROP CONSTRAINT IF EXISTS ck_channel_accounts_template_sync_status;

ALTER TABLE channel_accounts
    ADD CONSTRAINT ck_channel_accounts_template_sync_status
    CHECK (template_sync_status IN ('NEVER_SYNCED', 'SYNCING', 'SUCCEEDED', 'FAILED'));

CREATE INDEX IF NOT EXISTS ix_channel_accounts_template_sync_status
    ON channel_accounts(template_sync_status, template_last_synced_at);
