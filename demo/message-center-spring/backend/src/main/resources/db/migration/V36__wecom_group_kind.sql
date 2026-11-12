ALTER TABLE wecom_source_conversations
    ADD COLUMN group_kind varchar(20) NOT NULL DEFAULT 'UNKNOWN';

ALTER TABLE wecom_source_conversations
    ADD CONSTRAINT ck_wecom_source_conversation_group_kind
    CHECK (group_kind IN ('INTERNAL', 'EXTERNAL', 'UNKNOWN'));

-- A provider conversation key is an identifier, never a user-visible group name.
UPDATE wecom_source_conversations
SET display_name = NULL,
    updated_at = now()
WHERE conversation_type = 'GROUP'
  AND display_name ~ '^group:';

-- Existing non-key names were obtained through the external customer-group API.
UPDATE wecom_source_conversations
SET group_kind = 'EXTERNAL'
WHERE conversation_type = 'GROUP'
  AND nullif(display_name, '') IS NOT NULL;
