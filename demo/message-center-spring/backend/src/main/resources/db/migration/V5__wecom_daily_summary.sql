CREATE TABLE wecom_daily_summary_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    installation_id varchar(128) NOT NULL,
    auth_corp_id varchar(128) NOT NULL,
    summary_day date NOT NULL,
    user_id varchar(128) NOT NULL,
    external_user_id varchar(128) NOT NULL,
    slice_start integer NOT NULL,
    slice_end integer NOT NULL,
    message_count integer NOT NULL,
    message_digest char(64) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    wecom_job_id varchar(256),
    batch_summary text,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    deadline_at timestamptz NOT NULL,
    lease_owner varchar(100),
    lease_until timestamptz,
    last_error_code varchar(100),
    failure_state varchar(32),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    submitted_at timestamptz,
    completed_at timestamptz,
    CONSTRAINT uq_wecom_daily_summary_job_slice UNIQUE
        (installation_id, auth_corp_id, summary_day, user_id, external_user_id,
         slice_start, slice_end),
    CONSTRAINT ck_wecom_daily_summary_job_slice
        CHECK (slice_start >= 0 AND slice_end > slice_start
               AND message_count = slice_end - slice_start),
    CONSTRAINT ck_wecom_daily_summary_job_digest
        CHECK (message_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wecom_daily_summary_job_status
        CHECK (status IN ('PENDING', 'SUBMITTED', 'RETRY_WAIT', 'COMPLETED', 'FAILED', 'SUPERSEDED')),
    CONSTRAINT ck_wecom_daily_summary_job_attempts
        CHECK (attempt_count >= 0 AND attempt_count <= 20),
    CONSTRAINT ck_wecom_daily_summary_job_deadline
        CHECK (deadline_at > created_at),
    CONSTRAINT ck_wecom_daily_summary_job_value_bounds
        CHECK ((batch_summary IS NULL OR octet_length(batch_summary) <= 65536)
               AND (wecom_job_id IS NULL OR length(wecom_job_id) > 0))
);

CREATE INDEX ix_wecom_daily_summary_jobs_lease
    ON wecom_daily_summary_jobs (next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'SUBMITTED', 'RETRY_WAIT');

CREATE INDEX ix_wecom_daily_summary_jobs_group
    ON wecom_daily_summary_jobs
        (installation_id, auth_corp_id, summary_day, user_id, external_user_id, slice_start);

CREATE TABLE wecom_daily_summaries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    installation_id varchar(128) NOT NULL,
    auth_corp_id varchar(128) NOT NULL,
    summary_day date NOT NULL,
    user_id varchar(128) NOT NULL,
    external_user_id varchar(128) NOT NULL,
    summary text NOT NULL,
    message_count integer NOT NULL,
    completed_message_count integer NOT NULL,
    batch_count integer NOT NULL,
    completed_batch_count integer NOT NULL,
    completeness varchar(20) NOT NULL,
    generated_at timestamptz NOT NULL,
    CONSTRAINT uq_wecom_daily_summary_group UNIQUE
        (installation_id, auth_corp_id, summary_day, user_id, external_user_id),
    CONSTRAINT ck_wecom_daily_summary_counts CHECK (
        message_count > 0
        AND completed_message_count >= 0
        AND completed_message_count <= message_count
        AND batch_count > 0
        AND completed_batch_count > 0
        AND completed_batch_count <= batch_count
    ),
    CONSTRAINT ck_wecom_daily_summary_completeness
        CHECK (completeness IN ('COMPLETE', 'PARTIAL')),
    CONSTRAINT ck_wecom_daily_summary_text
        CHECK (length(btrim(summary)) > 0 AND octet_length(summary) <= 2097152)
);
