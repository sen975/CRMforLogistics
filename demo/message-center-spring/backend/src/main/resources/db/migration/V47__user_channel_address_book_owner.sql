-- User-scoped ownership for WhatsApp, email, phone and contact tags.
ALTER TABLE channel_accounts
    ADD COLUMN IF NOT EXISTS owner_user_id uuid REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE contacts
    ADD COLUMN IF NOT EXISTS owner_user_id uuid REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE call_records
    ADD COLUMN IF NOT EXISTS owner_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS contact_id uuid REFERENCES contacts(id) ON DELETE SET NULL;

ALTER TABLE contact_tags
    ADD COLUMN IF NOT EXISTS owner_user_id uuid REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS ix_channel_accounts_owner_channel
    ON channel_accounts (owner_user_id, channel_type, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_channel_accounts_owner_unique_active
    ON channel_accounts (owner_user_id, channel_type)
    WHERE owner_user_id IS NOT NULL
      AND channel_type IN ('chatapp', 'email')
      AND auth_status IN ('active', 'expired', 'failed')
      AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS ix_contacts_owner_sort
    ON contacts (owner_user_id, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS ix_call_records_owner_contact_time
    ON call_records (owner_user_id, contact_id, occurred_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS ix_contact_tags_owner_name
    ON contact_tags (owner_user_id, lower(name));

CREATE UNIQUE INDEX IF NOT EXISTS ux_contact_tags_owner_name
    ON contact_tags (owner_user_id, lower(name))
    WHERE owner_user_id IS NOT NULL;
