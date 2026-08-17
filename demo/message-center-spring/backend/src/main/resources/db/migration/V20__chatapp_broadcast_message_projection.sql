ALTER TABLE chatapp_broadcasts
    ADD COLUMN template_body_snapshot text,
    ADD COLUMN last_reconciliation_request_id varchar(255),
    ADD COLUMN last_reconciliation_provider_code varchar(100);

ALTER TABLE chatapp_broadcast_recipients
    ADD COLUMN message_id uuid REFERENCES messages(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX ux_chatapp_broadcast_recipient_message
    ON chatapp_broadcast_recipients(message_id)
    WHERE message_id IS NOT NULL;

ALTER TABLE chatapp_broadcast_jobs
    ADD CONSTRAINT uq_chatapp_broadcast_jobs_id_broadcast UNIQUE (id, broadcast_id);

ALTER TABLE chatapp_broadcast_recipients
    ADD CONSTRAINT uq_chatapp_broadcast_recipients_id_broadcast UNIQUE (id, broadcast_id);

CREATE TABLE chatapp_broadcast_reconciliation_evidence (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    broadcast_id uuid NOT NULL REFERENCES chatapp_broadcasts(id) ON DELETE CASCADE,
    job_id uuid NOT NULL,
    provider_request_id varchar(255),
    page_number integer NOT NULL,
    row_number integer NOT NULL,
    user_number varchar(50),
    provider_message_id varchar(255),
    provider_unique_message_id varchar(255),
    provider_status varchar(100),
    failure_reason varchar(1000),
    matched_recipient_id uuid,
    diagnostic_code varchar(100),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_chatapp_broadcast_evidence_page CHECK (page_number BETWEEN 1 AND 20),
    CONSTRAINT ck_chatapp_broadcast_evidence_row CHECK (row_number BETWEEN 0 AND 100),
    CONSTRAINT fk_chatapp_broadcast_evidence_job_scope
        FOREIGN KEY (job_id, broadcast_id)
        REFERENCES chatapp_broadcast_jobs (id, broadcast_id) ON DELETE CASCADE,
    CONSTRAINT fk_chatapp_broadcast_evidence_recipient_scope
        FOREIGN KEY (matched_recipient_id, broadcast_id)
        REFERENCES chatapp_broadcast_recipients (id, broadcast_id)
        ON DELETE SET NULL (matched_recipient_id),
    UNIQUE (job_id, page_number, row_number)
);

CREATE INDEX ix_chatapp_broadcast_evidence_listing
    ON chatapp_broadcast_reconciliation_evidence(broadcast_id, created_at DESC, id DESC);
