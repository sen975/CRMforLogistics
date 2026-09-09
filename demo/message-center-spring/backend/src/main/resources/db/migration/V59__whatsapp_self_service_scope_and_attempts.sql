ALTER TABLE whatsapp_provider_scopes
    ADD COLUMN IF NOT EXISTS scope_type varchar(40) NOT NULL DEFAULT 'ENTERPRISE_API',
    ADD COLUMN IF NOT EXISTS owner_user_id uuid REFERENCES users(id) ON DELETE SET NULL;

UPDATE whatsapp_provider_scopes
SET scope_type = 'ENTERPRISE_API'
WHERE scope_type IS NULL;

ALTER TABLE whatsapp_provider_scopes
    DROP CONSTRAINT IF EXISTS ck_whatsapp_provider_scope_type,
    DROP CONSTRAINT IF EXISTS ck_whatsapp_provider_scope_owner;

ALTER TABLE whatsapp_provider_scopes
    ADD CONSTRAINT ck_whatsapp_provider_scope_type
        CHECK (scope_type IN ('ENTERPRISE_API', 'EMPLOYEE_BUSINESS_APP')),
    ADD CONSTRAINT ck_whatsapp_provider_scope_owner
        CHECK ((scope_type = 'ENTERPRISE_API' AND owner_user_id IS NULL)
            OR (scope_type = 'EMPLOYEE_BUSINESS_APP' AND owner_user_id IS NOT NULL));

CREATE UNIQUE INDEX IF NOT EXISTS ux_whatsapp_provider_scope_provider_waba
    ON whatsapp_provider_scopes(provider, waba_id)
    WHERE waba_id IS NOT NULL;

ALTER TABLE whatsapp_authorization_attempts
    ADD COLUMN IF NOT EXISTS account_name varchar(100),
    ADD COLUMN IF NOT EXISTS account_remark varchar(500),
    ADD COLUMN IF NOT EXISTS completed_phone_number_id varchar(128),
    ADD COLUMN IF NOT EXISTS completed_account_id uuid REFERENCES channel_accounts(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS failure_stage varchar(64),
    ADD COLUMN IF NOT EXISTS failure_code varchar(128);

UPDATE whatsapp_authorization_attempts
SET status = 'COMPLETED'
WHERE status = 'CONSUMED';

ALTER TABLE whatsapp_authorization_attempts
    DROP CONSTRAINT IF EXISTS ck_whatsapp_authorization_attempt_status;

ALTER TABLE whatsapp_authorization_attempts
    ADD CONSTRAINT ck_whatsapp_authorization_attempt_status
        CHECK (status IN ('PENDING', 'META_COMPLETED', 'PROVIDER_SYNCED', 'COMPLETED',
            'CANCELLED', 'EXPIRED', 'FAILED'));

CREATE INDEX IF NOT EXISTS ix_whatsapp_authorization_attempt_completed_account
    ON whatsapp_authorization_attempts(completed_account_id)
    WHERE completed_account_id IS NOT NULL;
