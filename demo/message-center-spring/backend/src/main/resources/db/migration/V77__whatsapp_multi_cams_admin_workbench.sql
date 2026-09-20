ALTER TABLE whatsapp_provider_scopes
    ADD COLUMN IF NOT EXISTS display_name varchar(120),
    ADD COLUMN IF NOT EXISTS last_tested_at timestamptz,
    ADD COLUMN IF NOT EXISTS last_test_status varchar(20),
    ADD COLUMN IF NOT EXISTS last_test_error_code varchar(100),
    ADD COLUMN IF NOT EXISTS last_synced_at timestamptz,
    ADD COLUMN IF NOT EXISTS last_sync_status varchar(20),
    ADD COLUMN IF NOT EXISTS last_sync_error_code varchar(100),
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;

UPDATE whatsapp_provider_scopes
SET display_name = COALESCE(NULLIF(display_name, ''), external_scope_id)
WHERE display_name IS NULL OR display_name = '';

ALTER TABLE whatsapp_provider_scopes
    ALTER COLUMN display_name SET NOT NULL,
    ADD CONSTRAINT ck_whatsapp_provider_scope_version CHECK (version >= 0),
    ADD CONSTRAINT ck_whatsapp_provider_scope_test_status
        CHECK (last_test_status IS NULL OR last_test_status IN ('SUCCESS', 'FAILED')),
    ADD CONSTRAINT ck_whatsapp_provider_scope_sync_status
        CHECK (last_sync_status IS NULL OR last_sync_status IN ('SUCCESS', 'FAILED', 'RUNNING'));

CREATE INDEX IF NOT EXISTS ix_whatsapp_provider_scope_admin_queue
    ON whatsapp_provider_scopes(scope_type, status, created_at, id);
