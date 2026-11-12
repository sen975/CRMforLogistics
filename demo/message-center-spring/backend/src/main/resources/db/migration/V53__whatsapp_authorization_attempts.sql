CREATE TABLE whatsapp_authorization_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    state_hash varchar(128) NOT NULL UNIQUE,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    completed_waba_id varchar(64),
    completed_phone_number varchar(32),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_whatsapp_authorization_attempt_status
        CHECK (status IN ('PENDING', 'CONSUMED', 'EXPIRED', 'FAILED')),
    CONSTRAINT ck_whatsapp_authorization_attempt_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX ix_whatsapp_authorization_attempt_user_status
    ON whatsapp_authorization_attempts(user_id, status, expires_at);

CREATE TABLE whatsapp_phone_onboarding_operations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider_scope_id uuid NOT NULL REFERENCES whatsapp_provider_scopes(id),
    phone_number varchar(32) NOT NULL,
    country_code varchar(4) NOT NULL,
    verified_name varchar(100) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_whatsapp_phone_onboarding_operation UNIQUE (user_id, provider_scope_id, phone_number),
    CONSTRAINT ck_whatsapp_phone_onboarding_status
        CHECK (status IN ('PENDING', 'CODE_SENT', 'REGISTERED', 'FAILED', 'DISABLED'))
);
