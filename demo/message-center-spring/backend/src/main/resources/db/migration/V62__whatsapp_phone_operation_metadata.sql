ALTER TABLE whatsapp_phone_onboarding_operations
    ADD COLUMN IF NOT EXISTS account_name varchar(100),
    ADD COLUMN IF NOT EXISTS account_remark varchar(500),
    ADD COLUMN IF NOT EXISTS completed_account_id uuid REFERENCES channel_accounts(id) ON DELETE SET NULL;
