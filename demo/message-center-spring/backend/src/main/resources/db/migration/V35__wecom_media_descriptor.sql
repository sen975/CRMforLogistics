ALTER TABLE wecom_chatdata_messages
    ADD COLUMN media_json jsonb;

COMMENT ON COLUMN wecom_chatdata_messages.media_json IS
    'Bounded provider media identifiers only; never message body or credentials';
