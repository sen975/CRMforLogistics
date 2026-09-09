ALTER TABLE whatsapp_authorization_attempts
    ADD COLUMN onboarding_mode varchar(40) NOT NULL DEFAULT 'BUSINESS_APP_COEXISTENCE';

ALTER TABLE whatsapp_authorization_attempts
    ADD CONSTRAINT ck_whatsapp_authorization_attempt_mode
        CHECK (onboarding_mode IN ('BUSINESS_APP_COEXISTENCE', 'API_ONLY'));

ALTER TABLE whatsapp_authorization_attempts
    DROP COLUMN country_code,
    DROP COLUMN phone_number;
