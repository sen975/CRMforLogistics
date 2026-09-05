CREATE TABLE whatsapp_provider_scopes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider varchar(40) NOT NULL,
    external_scope_id varchar(255) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'READY',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_whatsapp_provider_scope UNIQUE (provider, external_scope_id),
    CONSTRAINT ck_whatsapp_provider_scope_status CHECK (status IN ('READY', 'BLOCKED'))
);

ALTER TABLE channel_accounts
    ADD COLUMN provider_scope_id uuid REFERENCES whatsapp_provider_scopes(id);

ALTER TABLE message_templates
    ADD COLUMN provider_scope_id uuid REFERENCES whatsapp_provider_scopes(id),
    ADD COLUMN created_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX ux_message_templates_shared_identity
    ON message_templates(provider_scope_id, provider_template_id, language_code)
    WHERE provider_scope_id IS NOT NULL;

CREATE TABLE template_change_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id uuid NOT NULL REFERENCES message_templates(id),
    change_type varchar(30) NOT NULL,
    requested_payload_jsonb jsonb NOT NULL,
    base_version bigint NOT NULL,
    requested_by_user_id uuid NOT NULL REFERENCES users(id),
    requested_via_account_id uuid NOT NULL REFERENCES channel_accounts(id),
    status varchar(30) NOT NULL,
    idempotency_key varchar(255) NOT NULL,
    reviewed_by_user_id uuid REFERENCES users(id),
    review_reason varchar(500),
    reviewed_at timestamptz,
    execution_started_at timestamptz,
    execution_completed_at timestamptz,
    execution_error_code varchar(100),
    execution_error_message text,
    provider_request_id varchar(255),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_template_change_type CHECK
        (change_type IN ('MODIFY', 'SET_SEND_PERMISSION', 'DELETE', 'BIND_MEDIA')),
    CONSTRAINT ck_template_change_status CHECK
        (status IN ('PENDING_APPROVAL', 'REJECTED', 'STALE', 'EXECUTING', 'SUCCEEDED', 'EXECUTION_FAILED')),
    CONSTRAINT ck_template_change_payload_size CHECK
        (octet_length(requested_payload_jsonb::text) <= 65536),
    CONSTRAINT ck_template_change_review_reason CHECK
        (status <> 'REJECTED' OR nullif(btrim(review_reason), '') IS NOT NULL),
    CONSTRAINT uq_template_change_idempotency UNIQUE (requested_by_user_id, idempotency_key)
);

CREATE UNIQUE INDEX ux_template_change_single_execution
    ON template_change_requests(template_id)
    WHERE status = 'EXECUTING';

ALTER TABLE template_operations
    ADD COLUMN template_id uuid REFERENCES message_templates(id),
    ADD COLUMN change_request_id uuid REFERENCES template_change_requests(id);

CREATE UNIQUE INDEX ux_template_operations_single_execution
    ON template_operations(template_id)
    WHERE template_id IS NOT NULL AND operation_status = 'PROCESSING';

CREATE TABLE template_media_bindings (
    template_id uuid NOT NULL REFERENCES message_templates(id),
    media_asset_id uuid NOT NULL REFERENCES template_media_assets(id),
    change_request_id uuid REFERENCES template_change_requests(id),
    bound_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (template_id, media_asset_id)
);

CREATE TABLE whatsapp_template_migration_state (
    migration_key varchar(64) PRIMARY KEY,
    status varchar(20) NOT NULL,
    provider_scope_id uuid REFERENCES whatsapp_provider_scopes(id),
    report_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    started_at timestamptz,
    completed_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_whatsapp_template_migration_key
        CHECK (migration_key = 'shared-template-v1'),
    CONSTRAINT ck_whatsapp_template_migration_status
        CHECK (status IN ('PENDING', 'RUNNING', 'READY', 'BLOCKED'))
);

CREATE TABLE whatsapp_template_migration_exceptions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    migration_key varchar(64) NOT NULL REFERENCES whatsapp_template_migration_state(migration_key),
    resource_type varchar(40) NOT NULL,
    resource_id uuid,
    reason_code varchar(100) NOT NULL,
    details_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_whatsapp_template_migration_details_size
        CHECK (octet_length(details_jsonb::text) <= 65536)
);

CREATE INDEX ix_template_change_review_queue
    ON template_change_requests(status, created_at, id);

CREATE INDEX ix_template_change_requester
    ON template_change_requests(requested_by_user_id, created_at DESC, id DESC);

CREATE INDEX ix_channel_accounts_provider_scope
    ON channel_accounts(provider_scope_id, created_at, id)
    WHERE deleted_at IS NULL AND channel_type IN ('chatapp', 'whatsapp');

CREATE INDEX ix_whatsapp_template_migration_exceptions
    ON whatsapp_template_migration_exceptions(migration_key, reason_code, created_at, id);
