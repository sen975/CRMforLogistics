CREATE TABLE wecom_message_summary_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    installation_id uuid NOT NULL REFERENCES wecom_installations(id),
    auth_corp_id varchar(128) NOT NULL,
    source_conversation_id uuid REFERENCES wecom_source_conversations(id),
    msgid varchar(256) NOT NULL,
    send_time bigint NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    wecom_job_id varchar(256),
    summary text,
    raw_request_json text NOT NULL,
    raw_response_json text,
    validation_stage varchar(40),
    last_error_code varchar(100),
    failure_state varchar(32),
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    lease_owner varchar(100),
    lease_until timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    submitted_at timestamptz,
    completed_at timestamptz,
    CONSTRAINT uq_wecom_message_summary_installation_msgid UNIQUE (installation_id, msgid),
    CONSTRAINT ck_wecom_message_summary_status CHECK
        (status IN ('PENDING', 'SUBMITTED', 'RETRY_WAIT', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_wecom_message_summary_attempts CHECK (attempt_count >= 0 AND attempt_count <= 100),
    CONSTRAINT ck_wecom_message_summary_values CHECK (
        length(btrim(msgid)) > 0
        AND send_time >= 0
        AND octet_length(raw_request_json) <= 16384
        AND (raw_response_json IS NULL OR octet_length(raw_response_json) <= 1048576)
        AND (summary IS NULL OR (length(btrim(summary)) > 0 AND octet_length(summary) <= 65536))
    )
);

CREATE INDEX ix_wecom_message_summary_lease
    ON wecom_message_summary_jobs (next_attempt_at, created_at, id)
    WHERE status IN ('PENDING', 'SUBMITTED', 'RETRY_WAIT');

CREATE INDEX ix_wecom_message_summary_conversation_time
    ON wecom_message_summary_jobs (source_conversation_id, send_time, id);

CREATE INDEX ix_wecom_message_summary_status_time
    ON wecom_message_summary_jobs (status, created_at DESC);
