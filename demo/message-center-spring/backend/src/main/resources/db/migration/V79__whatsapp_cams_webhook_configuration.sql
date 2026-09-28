CREATE TABLE whatsapp_cams_callback_configs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_scope_id uuid NOT NULL REFERENCES whatsapp_provider_scopes(id) ON DELETE CASCADE,
    channel_account_id uuid REFERENCES channel_accounts(id) ON DELETE CASCADE,
    level varchar(16) NOT NULL,
    desired_up_callback_url varchar(2048),
    desired_status_callback_url varchar(2048),
    http_flag varchar(1) NOT NULL DEFAULT 'Y',
    queue_flag varchar(1) NOT NULL DEFAULT 'N',
    provider_state varchar(20) NOT NULL DEFAULT 'UNKNOWN',
    last_apply_status varchar(20) NOT NULL DEFAULT 'NEVER_APPLIED',
    last_provider_request_id varchar(128),
    last_error_code varchar(128),
    last_applied_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_whatsapp_cams_callback_level CHECK (level IN ('PHONE', 'ACCOUNT')),
    CONSTRAINT ck_whatsapp_cams_callback_http_flag CHECK (http_flag IN ('Y', 'N')),
    CONSTRAINT ck_whatsapp_cams_callback_queue_flag CHECK (queue_flag IN ('Y', 'N')),
    CONSTRAINT ck_whatsapp_cams_callback_provider_state CHECK (provider_state IN ('UNKNOWN')),
    CONSTRAINT ck_whatsapp_cams_callback_apply_status
        CHECK (last_apply_status IN ('NEVER_APPLIED', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_whatsapp_cams_callback_level_fields
        CHECK ((level = 'ACCOUNT' AND channel_account_id IS NULL AND desired_up_callback_url IS NULL)
            OR (level = 'PHONE' AND channel_account_id IS NOT NULL)),
    CONSTRAINT ck_whatsapp_cams_callback_scope_provider
        CHECK (provider_scope_id IS NOT NULL)
);

CREATE UNIQUE INDEX ux_whatsapp_cams_callback_account
    ON whatsapp_cams_callback_configs(provider_scope_id)
    WHERE level = 'ACCOUNT';

CREATE UNIQUE INDEX ux_whatsapp_cams_callback_phone
    ON whatsapp_cams_callback_configs(provider_scope_id, channel_account_id)
    WHERE level = 'PHONE';

CREATE INDEX ix_whatsapp_cams_callback_scope
    ON whatsapp_cams_callback_configs(provider_scope_id, level, updated_at DESC);

CREATE TABLE whatsapp_cams_callback_audits (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_scope_id uuid NOT NULL REFERENCES whatsapp_provider_scopes(id) ON DELETE CASCADE,
    channel_account_id uuid REFERENCES channel_accounts(id) ON DELETE SET NULL,
    actor_user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    level varchar(16) NOT NULL,
    action varchar(16) NOT NULL,
    desired_url_host_hash varchar(128),
    desired_url_path_hash varchar(128),
    desired_url_https boolean NOT NULL DEFAULT false,
    expected_version bigint NOT NULL,
    result varchar(16) NOT NULL,
    error_code varchar(128),
    provider_request_id varchar(128),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_whatsapp_cams_callback_audit_level CHECK (level IN ('PHONE', 'ACCOUNT')),
    CONSTRAINT ck_whatsapp_cams_callback_audit_action CHECK (action IN ('APPLY', 'CLEAR')),
    CONSTRAINT ck_whatsapp_cams_callback_audit_result CHECK (result IN ('SUCCEEDED', 'FAILED'))
);

CREATE INDEX ix_whatsapp_cams_callback_audit_scope_time
    ON whatsapp_cams_callback_audits(provider_scope_id, created_at DESC);
