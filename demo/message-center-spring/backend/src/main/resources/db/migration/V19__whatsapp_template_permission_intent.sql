ALTER TABLE message_templates
    ADD COLUMN desired_allow_send boolean NOT NULL DEFAULT true,
    ADD COLUMN permission_sync_status varchar(20) NOT NULL DEFAULT 'IDLE',
    ADD COLUMN permission_sync_attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN permission_sync_next_attempt_at timestamptz,
    ADD COLUMN permission_sync_error_code varchar(100),
    ADD COLUMN permission_sync_error_message text;

ALTER TABLE message_templates
    ADD CONSTRAINT ck_template_permission_sync_status
        CHECK (permission_sync_status IN ('IDLE', 'PENDING', 'FAILED')),
    ADD CONSTRAINT ck_template_permission_attempt_count
        CHECK (permission_sync_attempt_count >= 0);

CREATE INDEX ix_message_templates_permission_reconcile
    ON message_templates(channel_account_id, permission_sync_next_attempt_at, updated_at)
    WHERE deleted_at IS NULL
      AND upper(status) = 'APPROVED'
      AND desired_allow_send <> allow_send;
