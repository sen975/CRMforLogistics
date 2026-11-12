ALTER TABLE email_submissions
    ADD COLUMN owner_user_id uuid REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX ix_email_submissions_owner_unknown
    ON email_submissions(owner_user_id, updated_at DESC, id DESC)
    WHERE status = 'UNKNOWN';
