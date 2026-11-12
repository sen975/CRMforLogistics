UPDATE whatsapp_authorization_attempts
SET onboarding_mode = CASE onboarding_mode
    WHEN 'BUSINESS_APP_COEXISTENCE' THEN 'EMPLOYEE_BUSINESS_APP'
    WHEN 'API_ONLY' THEN 'ADMIN_API_WABA'
    ELSE onboarding_mode
END;

UPDATE channel_accounts
SET onboarding_mode = CASE onboarding_mode
    WHEN 'BUSINESS_APP_COEXISTENCE' THEN 'EMPLOYEE_BUSINESS_APP'
    WHEN 'API_ONLY' THEN 'ADMIN_API_WABA'
    ELSE onboarding_mode
END
WHERE onboarding_mode IS NOT NULL;

ALTER TABLE whatsapp_authorization_attempts
    DROP CONSTRAINT IF EXISTS ck_whatsapp_authorization_attempt_mode;

ALTER TABLE whatsapp_authorization_attempts
    ADD CONSTRAINT ck_whatsapp_authorization_attempt_mode
        CHECK (onboarding_mode IN ('EMPLOYEE_BUSINESS_APP', 'ADMIN_API_WABA'));

ALTER TABLE channel_accounts
    DROP CONSTRAINT IF EXISTS ck_channel_accounts_onboarding_mode;

ALTER TABLE channel_accounts
    ADD CONSTRAINT ck_channel_accounts_onboarding_mode
        CHECK (onboarding_mode IS NULL OR onboarding_mode IN
            ('EMPLOYEE_BUSINESS_APP', 'ADMIN_API_WABA'));
