ALTER TABLE email_submissions
    ADD COLUMN lease_token uuid,
    ADD COLUMN lease_expires_at timestamptz;

UPDATE email_submissions
SET lease_token = gen_random_uuid(),
    lease_expires_at = CASE
        WHEN status IN ('PENDING', 'SMTP_SENT') THEN now() + interval '5 minutes'
        ELSE now()
    END;

ALTER TABLE email_submissions
    ALTER COLUMN lease_token SET NOT NULL,
    ALTER COLUMN lease_expires_at SET NOT NULL;

CREATE INDEX ix_email_submissions_owner_lease_expiry
    ON email_submissions(owner_user_id, lease_expires_at, id)
    WHERE status IN ('PENDING', 'SMTP_SENT');
