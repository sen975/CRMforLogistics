ALTER TABLE whatsapp_provider_scopes
    ADD COLUMN waba_id varchar(64),
    ADD COLUMN identity_status varchar(32) NOT NULL DEFAULT 'IDENTITY_PENDING',
    ADD COLUMN encrypted_config jsonb NOT NULL DEFAULT '{}'::jsonb;

UPDATE whatsapp_provider_scopes scope
SET encrypted_config = account.encrypted_config
FROM channel_accounts account
WHERE account.provider_scope_id = scope.id
  AND scope.encrypted_config = '{}'::jsonb
  AND account.encrypted_config <> '{}'::jsonb;

ALTER TABLE whatsapp_provider_scopes
    ADD CONSTRAINT ck_whatsapp_provider_scope_waba
        CHECK (waba_id IS NULL OR btrim(waba_id) <> ''),
    ADD CONSTRAINT ck_whatsapp_provider_scope_identity_status
        CHECK (identity_status IN ('IDENTITY_PENDING', 'IDENTITY_VERIFIED'));

CREATE UNIQUE INDEX ux_whatsapp_provider_scope_waba
    ON whatsapp_provider_scopes(provider, waba_id)
    WHERE waba_id IS NOT NULL;

ALTER TABLE channel_accounts
    ADD COLUMN onboarding_mode varchar(40),
    ADD COLUMN phone_verification_status varchar(40),
    ADD COLUMN provider_phone_status varchar(40);

ALTER TABLE channel_accounts
    ADD CONSTRAINT ck_channel_accounts_onboarding_mode
        CHECK (onboarding_mode IS NULL OR onboarding_mode IN ('BUSINESS_APP_COEXISTENCE', 'API_ONLY')),
    ADD CONSTRAINT ck_channel_accounts_phone_verification_status
        CHECK (phone_verification_status IS NULL OR phone_verification_status IN
            ('PENDING', 'CODE_SENT', 'VERIFIED', 'FAILED')),
    ADD CONSTRAINT ck_channel_accounts_provider_phone_status
        CHECK (provider_phone_status IS NULL OR provider_phone_status IN
            ('PENDING', 'ACTIVE', 'DISABLED', 'FAILED', 'UNKNOWN'));
