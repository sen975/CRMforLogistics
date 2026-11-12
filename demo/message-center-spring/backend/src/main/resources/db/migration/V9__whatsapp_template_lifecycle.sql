ALTER TABLE message_templates ADD COLUMN category varchar(30);
ALTER TABLE message_templates ADD COLUMN template_type varchar(30) NOT NULL DEFAULT 'WHATSAPP';
ALTER TABLE message_templates ADD COLUMN components_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE message_templates ADD COLUMN examples_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE message_templates ADD COLUMN message_send_ttl_seconds integer;
ALTER TABLE message_templates ADD COLUMN allow_send boolean NOT NULL DEFAULT false;
ALTER TABLE message_templates ADD COLUMN provider_audit_status varchar(100);
ALTER TABLE message_templates ADD COLUMN rejection_reason text;
ALTER TABLE message_templates ADD COLUMN quality_score varchar(100);
ALTER TABLE message_templates ADD COLUMN deleted_at timestamptz;
ALTER TABLE message_templates ADD COLUMN version bigint NOT NULL DEFAULT 0;

CREATE TABLE template_operations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_account_id uuid NOT NULL REFERENCES channel_accounts(id),
    idempotency_key varchar(255) NOT NULL,
    operation_type varchar(40) NOT NULL,
    provider_template_id varchar(255),
    language_code varchar(30) NOT NULL,
    requested_snapshot_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    operation_status varchar(40) NOT NULL,
    provider_request_id varchar(255),
    provider_code varchar(100),
    error_code varchar(100),
    error_message text,
    next_reconcile_at timestamptz,
    reconcile_attempt_count integer NOT NULL DEFAULT 0,
    actor_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    trace_id varchar(100),
    lease_owner varchar(100),
    lease_until timestamptz,
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT ck_template_operation_type CHECK
        (operation_type IN ('CREATE','MODIFY','SET_SEND_PERMISSION','DELETE','RECONCILE')),
    CONSTRAINT ck_template_operation_status CHECK
        (operation_status IN ('PROCESSING','SUCCEEDED','SUBMISSION_UNKNOWN','FAILED')),
    CONSTRAINT ck_template_reconcile_attempts CHECK
        (reconcile_attempt_count BETWEEN 0 AND 10)
);

CREATE TABLE template_media_assets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_account_id uuid NOT NULL REFERENCES channel_accounts(id),
    provider_object_key text NOT NULL,
    provider_url text NOT NULL,
    media_format varchar(20) NOT NULL,
    content_type varchar(100) NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 char(64) NOT NULL,
    asset_status varchar(20) NOT NULL,
    created_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    attached_at timestamptz,
    CONSTRAINT ck_template_media_format CHECK
        (media_format IN ('IMAGE','VIDEO','DOCUMENT')),
    CONSTRAINT ck_template_asset_status CHECK
        (asset_status IN ('UPLOADED','ATTACHED','ATTACHMENT_UNKNOWN','ORPHANED')),
    CONSTRAINT ck_template_asset_size CHECK (size_bytes > 0)
);

CREATE UNIQUE INDEX ux_template_operations_idempotency
ON template_operations(channel_account_id, idempotency_key);

CREATE INDEX ix_message_templates_admin_listing
ON message_templates(channel_account_id, deleted_at, status, category, language_code, updated_at DESC);

CREATE INDEX ix_template_operations_unknown_reconciliation
ON template_operations(next_reconcile_at, started_at)
WHERE operation_status = 'SUBMISSION_UNKNOWN' AND reconcile_attempt_count < 10;

CREATE INDEX ix_template_media_assets_attachment
ON template_media_assets(channel_account_id, asset_status, created_at DESC);
