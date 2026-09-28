CREATE TABLE email_submissions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_account_id uuid REFERENCES channel_accounts(id) ON DELETE SET NULL,
    provider_message_id varchar(512), recipient varchar(512) NOT NULL,
    subject varchar(998) NOT NULL DEFAULT '', status varchar(32) NOT NULL,
    last_error varchar(2048), created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_email_submission_status CHECK (status IN ('PENDING','SMTP_SENT','SENT','UNKNOWN','FAILED'))
);
CREATE INDEX ix_email_submissions_retryable ON email_submissions(status, updated_at);
