-- Make reminder claims recoverable after a worker crashes between claim and send.
ALTER TABLE todo_daily_reminders
    ADD COLUMN claim_token varchar(64),
    ADD COLUMN claimed_at timestamptz;

CREATE INDEX ix_todo_daily_reminders_expired_claim
    ON todo_daily_reminders (claimed_at)
    WHERE status = 'PENDING' AND claimed_at IS NOT NULL;

ALTER TABLE todo_items
    ADD COLUMN lead_reminder_claim_token varchar(64),
    ADD COLUMN lead_reminder_claimed_at timestamptz;

CREATE INDEX ix_todo_items_expired_lead_claim
    ON todo_items (lead_reminder_claimed_at)
    WHERE completed = false AND lead_reminder_sent_at IS NULL
        AND lead_reminder_claimed_at IS NOT NULL;
