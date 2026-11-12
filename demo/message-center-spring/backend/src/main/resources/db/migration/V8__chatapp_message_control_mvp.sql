ALTER TABLE contact_identities
    DROP CONSTRAINT ck_contact_identities_channel;

ALTER TABLE contact_identities
    ADD CONSTRAINT ck_contact_identities_channel
        CHECK (channel_type IN ('email', 'whatsapp', 'chatapp', 'wecom', 'phone'));
