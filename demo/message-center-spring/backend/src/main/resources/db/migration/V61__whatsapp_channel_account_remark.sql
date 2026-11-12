ALTER TABLE channel_accounts
    ADD COLUMN IF NOT EXISTS remark varchar(500);
