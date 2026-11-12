CREATE TABLE channel_accounts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_type varchar(30) NOT NULL,
    name varchar(100) NOT NULL,
    account_identifier varchar(255) NOT NULL,
    account_identifier_normalized varchar(255) NOT NULL,
    auth_status varchar(30) NOT NULL DEFAULT 'unbound',
    sync_status varchar(30) NOT NULL DEFAULT 'idle',
    encrypted_config jsonb NOT NULL,
    last_synced_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_channel_accounts_auth CHECK (auth_status IN ('unbound', 'active', 'expired', 'failed', 'disabled')),
    CONSTRAINT ck_channel_accounts_sync CHECK (sync_status IN ('idle', 'syncing', 'success', 'failed'))
);

CREATE UNIQUE INDEX ux_channel_accounts_identifier
    ON channel_accounts (channel_type, account_identifier_normalized)
    WHERE deleted_at IS NULL;

CREATE TABLE channel_sync_cursors (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_account_id uuid NOT NULL REFERENCES channel_accounts (id),
    cursor_type varchar(50) NOT NULL,
    scope_key varchar(255) NOT NULL,
    cursor_value text,
    cursor_timestamp timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_channel_sync_cursor UNIQUE (channel_account_id, cursor_type, scope_key)
);

CREATE TABLE message_templates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_account_id uuid NOT NULL REFERENCES channel_accounts (id),
    provider_template_id varchar(255) NOT NULL,
    language_code varchar(30) NOT NULL,
    name varchar(255) NOT NULL,
    body text NOT NULL,
    status varchar(30) NOT NULL,
    provider_updated_at timestamptz,
    metadata_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    last_synced_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_message_template UNIQUE (channel_account_id, provider_template_id, language_code)
);

CREATE TABLE conversations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_account_id uuid NOT NULL REFERENCES channel_accounts (id),
    contact_identity_id uuid NOT NULL REFERENCES contact_identities (id),
    status varchar(20) NOT NULL DEFAULT 'open',
    assigned_team_id uuid REFERENCES teams (id) ON DELETE SET NULL,
    assigned_user_id uuid REFERENCES users (id) ON DELETE SET NULL,
    next_ingest_sequence bigint NOT NULL DEFAULT 0,
    last_message_id uuid,
    last_message_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_conversations_status CHECK (status IN ('open', 'closed', 'archived')),
    CONSTRAINT ck_conversations_sequence CHECK (next_ingest_sequence >= 0),
    CONSTRAINT uq_conversation_account_identity UNIQUE (channel_account_id, contact_identity_id),
    CONSTRAINT uq_conversation_account_pair UNIQUE (id, channel_account_id)
);

CREATE TABLE conversation_access_grants (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid NOT NULL REFERENCES conversations (id),
    user_id uuid NOT NULL REFERENCES users (id),
    granted_by uuid NOT NULL REFERENCES users (id),
    reason text NOT NULL,
    granted_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    revoked_at timestamptz,
    revoked_by uuid REFERENCES users (id),
    CONSTRAINT ck_conversation_grant_expiry CHECK (expires_at IS NULL OR expires_at > granted_at)
);

CREATE UNIQUE INDEX ux_conversation_access_grant_active
    ON conversation_access_grants (conversation_id, user_id) WHERE revoked_at IS NULL;

CREATE TABLE conversation_read_states (
    conversation_id uuid NOT NULL REFERENCES conversations (id),
    user_id uuid NOT NULL REFERENCES users (id),
    last_read_sequence bigint NOT NULL DEFAULT 0,
    last_read_message_id uuid,
    last_read_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, user_id),
    CONSTRAINT ck_conversation_read_sequence CHECK (last_read_sequence >= 0)
);

CREATE TABLE messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid NOT NULL,
    channel_account_id uuid NOT NULL REFERENCES channel_accounts (id),
    source_event_id uuid,
    provider_message_id varchar(255),
    client_request_id varchar(255),
    direction varchar(20) NOT NULL,
    message_kind varchar(30) NOT NULL,
    subject text,
    body_text text,
    body_html text,
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    ingest_sequence bigint NOT NULL,
    counts_as_unread boolean NOT NULL DEFAULT true,
    current_status varchar(30) NOT NULL,
    current_status_at timestamptz NOT NULL,
    created_by_user_id uuid REFERENCES users (id) ON DELETE SET NULL,
    metadata_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_messages_direction CHECK (direction IN ('inbound', 'outbound', 'system')),
    CONSTRAINT ck_messages_kind CHECK (message_kind IN ('text', 'template', 'image', 'video', 'document', 'email', 'system')),
    CONSTRAINT ck_messages_status CHECK (current_status IN ('pending', 'processing', 'submission_unknown', 'submitted', 'sent', 'delivered', 'read', 'failed', 'cancelled')),
    CONSTRAINT ck_messages_ingest_sequence CHECK (ingest_sequence > 0),
    CONSTRAINT fk_message_conversation_account
        FOREIGN KEY (conversation_id, channel_account_id)
        REFERENCES conversations (id, channel_account_id)
);

CREATE UNIQUE INDEX ux_messages_provider_id
    ON messages (channel_account_id, provider_message_id)
    WHERE provider_message_id IS NOT NULL;

CREATE UNIQUE INDEX ux_messages_client_request_id
    ON messages (channel_account_id, client_request_id)
    WHERE client_request_id IS NOT NULL;

CREATE UNIQUE INDEX ux_messages_ingest_sequence
    ON messages (conversation_id, ingest_sequence);

ALTER TABLE conversations
    ADD CONSTRAINT fk_conversations_last_message
    FOREIGN KEY (last_message_id) REFERENCES messages (id);

ALTER TABLE conversation_read_states
    ADD CONSTRAINT fk_conversation_read_last_message
    FOREIGN KEY (last_read_message_id) REFERENCES messages (id);

CREATE TABLE message_participants (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id uuid NOT NULL REFERENCES messages (id),
    participant_role varchar(20) NOT NULL,
    contact_identity_id uuid REFERENCES contact_identities (id),
    display_address varchar(500),
    normalized_address varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_message_participants_role CHECK (participant_role IN ('sender', 'to', 'cc', 'bcc'))
);

CREATE INDEX ix_message_participants_message ON message_participants (message_id);

CREATE TABLE message_status_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id uuid NOT NULL REFERENCES messages (id),
    status varchar(30) NOT NULL,
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    provider_event_id varchar(255),
    reason_code varchar(100),
    reason_message text,
    metadata_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ck_message_status_events_status CHECK (status IN ('pending', 'processing', 'submission_unknown', 'submitted', 'sent', 'delivered', 'read', 'failed', 'cancelled'))
);

CREATE UNIQUE INDEX ux_message_status_provider_event
    ON message_status_events (message_id, provider_event_id)
    WHERE provider_event_id IS NOT NULL;

CREATE INDEX ix_message_status_events_message_time
    ON message_status_events (message_id, occurred_at, id);
