INSERT INTO roles (code, display_name)
VALUES ('broadcast_sender', 'Broadcast Sender')
ON CONFLICT (code) DO NOTHING;

CREATE TABLE chatapp_broadcasts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_account_id uuid NOT NULL REFERENCES channel_accounts (id),
    name varchar(120) NOT NULL,
    template_code varchar(200) NOT NULL,
    template_name varchar(200) NOT NULL,
    language_code varchar(50) NOT NULL,
    recipient_count integer NOT NULL,
    success_count integer NOT NULL DEFAULT 0,
    failed_count integer NOT NULL DEFAULT 0,
    processing_count integer NOT NULL,
    status varchar(40) NOT NULL,
    client_request_id varchar(255) NOT NULL,
    request_fingerprint char(64) NOT NULL,
    provider_group_message_id varchar(255),
    provider_request_id varchar(255),
    provider_code varchar(100),
    error_code varchar(100),
    error_message varchar(1000),
    retries_broadcast_id uuid REFERENCES chatapp_broadcasts (id),
    created_by_user_id uuid NOT NULL REFERENCES users (id),
    submitted_at timestamptz,
    reconciled_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (channel_account_id, client_request_id),
    CONSTRAINT ck_chatapp_broadcast_recipient_count CHECK (recipient_count BETWEEN 1 AND 1000),
    CONSTRAINT ck_chatapp_broadcast_counts CHECK (
        success_count >= 0 AND failed_count >= 0 AND processing_count >= 0
        AND success_count + failed_count + processing_count = recipient_count
    ),
    CONSTRAINT ck_chatapp_broadcast_status CHECK (status IN (
        'DRAFT', 'QUEUED', 'SUBMITTING', 'SUBMITTED', 'RECONCILING',
        'SUCCEEDED', 'PARTIALLY_FAILED', 'FAILED', 'SUBMISSION_UNKNOWN',
        'STATUS_UNKNOWN', 'CANCELLED'
    )),
    CONSTRAINT ck_chatapp_broadcast_request_id CHECK (
        client_request_id ~ '^[A-Za-z0-9._~:-]{1,255}$'
    ),
    CONSTRAINT ck_chatapp_broadcast_fingerprint CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX ix_chatapp_broadcasts_listing
    ON chatapp_broadcasts (channel_account_id, created_at DESC, id DESC);
CREATE INDEX ix_chatapp_broadcasts_status
    ON chatapp_broadcasts (status, updated_at);

CREATE TABLE chatapp_broadcast_recipients (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    broadcast_id uuid NOT NULL REFERENCES chatapp_broadcasts (id) ON DELETE CASCADE,
    contact_id uuid NOT NULL REFERENCES contacts (id),
    contact_identity_id uuid NOT NULL REFERENCES contact_identities (id),
    recipient_name_snapshot varchar(200) NOT NULL,
    recipient_number_snapshot varchar(50) NOT NULL,
    template_params_jsonb jsonb NOT NULL DEFAULT '{}'::jsonb,
    provider_message_id varchar(255),
    provider_unique_message_id varchar(255),
    status varchar(40) NOT NULL DEFAULT 'QUEUED',
    failure_reason varchar(1000),
    provider_sent_at timestamptz,
    last_reconciled_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (broadcast_id, contact_identity_id),
    CONSTRAINT ck_chatapp_broadcast_recipient_status CHECK (status IN (
        'QUEUED', 'PROCESSING', 'SENT', 'DELIVERED', 'READ', 'FAILED_RECIPIENT'
    )),
    CONSTRAINT ck_chatapp_broadcast_recipient_params_object CHECK (
        jsonb_typeof(template_params_jsonb) = 'object'
    )
);

CREATE INDEX ix_chatapp_broadcast_recipients_failures
    ON chatapp_broadcast_recipients (broadcast_id, status, updated_at DESC);
CREATE INDEX ix_chatapp_broadcast_recipients_number
    ON chatapp_broadcast_recipients (broadcast_id, recipient_number_snapshot);

CREATE TABLE chatapp_broadcast_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    broadcast_id uuid NOT NULL REFERENCES chatapp_broadcasts (id) ON DELETE CASCADE,
    job_type varchar(20) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_id varchar(64),
    lease_worker_id varchar(128),
    lease_expires_at timestamptz,
    last_error_code varchar(100),
    last_error_message varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_chatapp_broadcast_job_type CHECK (job_type IN ('SUBMIT', 'RECONCILE')),
    CONSTRAINT ck_chatapp_broadcast_job_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'DEAD')
    ),
    CONSTRAINT ck_chatapp_broadcast_job_attempts CHECK (
        attempt_count >= 0 AND max_attempts BETWEEN 1 AND 20 AND attempt_count <= max_attempts
    ),
    CONSTRAINT ck_chatapp_broadcast_job_lease CHECK (
        (lease_id IS NULL AND lease_worker_id IS NULL AND lease_expires_at IS NULL)
        OR (lease_id IS NOT NULL AND lease_worker_id IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX ux_chatapp_broadcast_active_job
    ON chatapp_broadcast_jobs (broadcast_id, job_type)
    WHERE status IN ('PENDING', 'PROCESSING', 'FAILED');
CREATE INDEX ix_chatapp_broadcast_jobs_due
    ON chatapp_broadcast_jobs (next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'FAILED');
