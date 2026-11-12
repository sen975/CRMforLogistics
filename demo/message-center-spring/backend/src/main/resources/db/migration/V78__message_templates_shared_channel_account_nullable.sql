-- An ENTERPRISE_API template belongs to a provider scope (one bound CAMS space) and is shared by
-- every channel account bound to it, so the writes store it with a null channel_account_id.
-- The column kept the NOT NULL it was created with, which made the first write of every provider
-- template fail, and left shared templates only updatable, never insertable.
ALTER TABLE message_templates
    ALTER COLUMN channel_account_id DROP NOT NULL;
