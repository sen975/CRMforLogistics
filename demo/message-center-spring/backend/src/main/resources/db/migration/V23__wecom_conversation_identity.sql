-- Source-of-truth identity model for WeCom ChatData. Existing CRM rows remain readable.
ALTER TABLE wecom_installations ADD COLUMN corp_name varchar(512);
CREATE TABLE wecom_parties (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    installation_id uuid NOT NULL REFERENCES wecom_installations (id),
    party_type varchar(20) NOT NULL,
    provider_party_id varchar(256) NOT NULL,
    display_name varchar(512),
    avatar_url text,
    profile_status varchar(20) NOT NULL DEFAULT 'PENDING',
    profile_error_code varchar(64),
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_wecom_party_provider UNIQUE (installation_id, party_type, provider_party_id),
    CONSTRAINT ck_wecom_party_type CHECK (party_type IN ('EMPLOYEE', 'EXTERNAL_CONTACT', 'GROUP')),
    CONSTRAINT ck_wecom_party_profile_status CHECK (profile_status IN ('READY', 'PARTIAL', 'DEGRADED', 'PENDING'))
);

CREATE INDEX ix_wecom_parties_installation_type
    ON wecom_parties (installation_id, party_type, display_name);

CREATE TABLE wecom_source_conversations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    installation_id uuid NOT NULL REFERENCES wecom_installations (id),
    provider_conversation_key varchar(512) NOT NULL,
    conversation_type varchar(20) NOT NULL,
    display_name varchar(512),
    avatar_url text,
    contact_identity_id uuid REFERENCES contact_identities (id),
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_wecom_source_conversation UNIQUE (installation_id, provider_conversation_key),
    CONSTRAINT ck_wecom_source_conversation_type CHECK (conversation_type IN ('DIRECT', 'GROUP')),
    CONSTRAINT ck_wecom_group_without_contact CHECK (
        conversation_type <> 'GROUP' OR contact_identity_id IS NULL
    )
);

CREATE INDEX ix_wecom_source_conversations_type
    ON wecom_source_conversations (installation_id, conversation_type, last_seen_at DESC);

CREATE TABLE wecom_source_conversation_participants (
    source_conversation_id uuid NOT NULL REFERENCES wecom_source_conversations (id) ON DELETE CASCADE,
    party_id uuid NOT NULL REFERENCES wecom_parties (id),
    participant_status varchar(20) NOT NULL DEFAULT 'OBSERVED',
    first_observed_at timestamptz NOT NULL DEFAULT now(),
    last_observed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source_conversation_id, party_id),
    CONSTRAINT ck_wecom_participant_status CHECK (participant_status IN ('OBSERVED', 'LEFT'))
);

ALTER TABLE wecom_chatdata_messages
    ADD COLUMN installation_id uuid REFERENCES wecom_installations (id),
    ADD COLUMN source_conversation_id uuid REFERENCES wecom_source_conversations (id),
    ADD COLUMN sender_party_id uuid REFERENCES wecom_parties (id),
    ADD COLUMN receiver_party_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN ingest_status varchar(20) NOT NULL DEFAULT 'stored';

ALTER TABLE wecom_chatdata_messages
    ALTER COLUMN external_userid DROP NOT NULL;

ALTER TABLE wecom_chatdata_messages
    ALTER COLUMN userid DROP NOT NULL;

ALTER TABLE wecom_chatdata_messages
    ADD CONSTRAINT ck_wecom_chatdata_ingest_status
        CHECK (ingest_status IN ('stored', 'direct', 'group', 'duplicate', 'failed'));

CREATE UNIQUE INDEX ux_wecom_chatdata_installation_msgid
    ON wecom_chatdata_messages (installation_id, msgid)
    WHERE installation_id IS NOT NULL;

CREATE INDEX ix_wecom_chatdata_source_conversation
    ON wecom_chatdata_messages (source_conversation_id, send_time, msgid);

CREATE TABLE wecom_chatdata_ingest_failures (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    installation_id uuid NOT NULL REFERENCES wecom_installations (id),
    cursor_key varchar(128) NOT NULL,
    cursor_from varchar(128),
    cursor_to varchar(128),
    msgid_digest varchar(64),
    failure_stage varchar(64) NOT NULL,
    error_code varchar(128) NOT NULL,
    retry_count integer NOT NULL DEFAULT 0,
    first_failed_at timestamptz NOT NULL DEFAULT now(),
    last_failed_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    CONSTRAINT ck_wecom_ingest_retry_count CHECK (retry_count >= 0)
);

CREATE INDEX ix_wecom_chatdata_ingest_failures_retry
    ON wecom_chatdata_ingest_failures (installation_id, resolved_at, last_failed_at DESC);

ALTER TABLE conversations
    ALTER COLUMN contact_identity_id DROP NOT NULL;

ALTER TABLE conversations
    ADD COLUMN source_conversation_id uuid REFERENCES wecom_source_conversations (id);

CREATE UNIQUE INDEX ux_conversation_source_identity
    ON conversations (channel_account_id, source_conversation_id)
    WHERE source_conversation_id IS NOT NULL;
