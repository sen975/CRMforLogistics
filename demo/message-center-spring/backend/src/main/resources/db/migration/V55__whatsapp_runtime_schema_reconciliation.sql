ALTER TABLE whatsapp_provider_scopes
    ADD COLUMN IF NOT EXISTS encrypted_config jsonb NOT NULL DEFAULT '{}'::jsonb;

CREATE TABLE IF NOT EXISTS whatsapp_phone_onboarding_operations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider_scope_id uuid NOT NULL REFERENCES whatsapp_provider_scopes(id),
    phone_number varchar(32) NOT NULL,
    country_code varchar(4) NOT NULL,
    verified_name varchar(100) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_whatsapp_phone_onboarding_operation
        UNIQUE (user_id, provider_scope_id, phone_number),
    CONSTRAINT ck_whatsapp_phone_onboarding_status
        CHECK (status IN ('PENDING', 'CODE_SENT', 'REGISTERED', 'FAILED', 'DISABLED'))
);

CREATE INDEX IF NOT EXISTS ix_whatsapp_phone_onboarding_user_status
    ON whatsapp_phone_onboarding_operations(user_id, status, updated_at DESC);
