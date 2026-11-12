ALTER TABLE whatsapp_cams_callback_configs
    ADD COLUMN IF NOT EXISTS apply_token uuid,
    ADD COLUMN IF NOT EXISTS apply_started_at timestamptz;

ALTER TABLE whatsapp_cams_callback_configs
    DROP CONSTRAINT IF EXISTS ck_whatsapp_cams_callback_apply_status;

ALTER TABLE whatsapp_cams_callback_configs
    ADD CONSTRAINT ck_whatsapp_cams_callback_apply_status
    CHECK (last_apply_status IN ('NEVER_APPLIED', 'APPLYING', 'SUCCEEDED', 'FAILED', 'SUBMISSION_UNKNOWN'));

CREATE INDEX IF NOT EXISTS ix_whatsapp_cams_callback_apply_claim
    ON whatsapp_cams_callback_configs(id, version, apply_token);
