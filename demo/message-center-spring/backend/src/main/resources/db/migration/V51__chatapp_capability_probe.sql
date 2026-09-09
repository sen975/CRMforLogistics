CREATE TABLE chatapp_capability_results (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id uuid NOT NULL,
    channel_account_id uuid REFERENCES channel_accounts(id),
    provider_scope_id uuid REFERENCES whatsapp_provider_scopes(id),
    phase varchar(32) NOT NULL,
    action varchar(80) NOT NULL,
    status varchar(40) NOT NULL,
    provider_request_id varchar(255),
    diagnostic_code varchar(128),
    diagnostic_message varchar(1000),
    tested_phone_last4 varchar(4),
    tested_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_chatapp_capability_phase CHECK
        (phase IN ('READ_ONLY', 'PROVISIONING_TEST', 'MIGRATION_REVIEW')),
    CONSTRAINT ck_chatapp_capability_status CHECK
        (status IN ('VERIFIED', 'FAILED', 'UNVERIFIED_FOR_PRODUCTION')),
    CONSTRAINT ck_chatapp_capability_phone_last4 CHECK
        (tested_phone_last4 IS NULL OR tested_phone_last4 ~ '^[0-9]{1,4}$')
);

CREATE INDEX ix_chatapp_capability_latest
    ON chatapp_capability_results(tested_at DESC, created_at DESC, report_id);
