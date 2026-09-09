CREATE TABLE whatsapp_account_assignment_audits (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_account_id uuid NOT NULL REFERENCES channel_accounts(id),
    previous_owner_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    next_owner_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    actor_user_id uuid NOT NULL REFERENCES users(id),
    action varchar(20) NOT NULL,
    reason varchar(500) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_whatsapp_account_assignment_action CHECK (action IN ('ASSIGN', 'DISABLE'))
);

CREATE INDEX ix_whatsapp_account_assignment_audit_account_time
    ON whatsapp_account_assignment_audits(channel_account_id, created_at DESC);
