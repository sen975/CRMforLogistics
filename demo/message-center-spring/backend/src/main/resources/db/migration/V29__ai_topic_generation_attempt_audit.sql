CREATE TABLE ai_topic_generation_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    generation_job_id uuid NOT NULL REFERENCES ai_topic_generation_jobs(id) ON DELETE CASCADE,
    contact_id uuid NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    attempt_number integer NOT NULL,
    provider_host varchar(255),
    model varchar(200),
    request_payload jsonb,
    request_truncated boolean NOT NULL DEFAULT false,
    response_status integer,
    response_headers jsonb,
    raw_response_body text,
    response_truncated boolean NOT NULL DEFAULT false,
    parsed_response jsonb,
    stage varchar(40) NOT NULL,
    status varchar(20) NOT NULL,
    error_code varchar(100),
    error_diagnostic text,
    duration_ms bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT ck_ai_topic_attempt_stage CHECK (stage IN ('REQUEST_BUILD','PROVIDER_CALL','RESPONSE_PARSE','RESPONSE_VALIDATE','BUSINESS_APPLY','COMPLETED')),
    CONSTRAINT ck_ai_topic_attempt_status CHECK (status IN ('STARTED','SUCCEEDED','FAILED')),
    CONSTRAINT ck_ai_topic_attempt_number CHECK (attempt_number > 0)
);

CREATE INDEX ix_ai_topic_attempt_job ON ai_topic_generation_attempts(generation_job_id, attempt_number);
CREATE INDEX ix_ai_topic_attempt_contact_time ON ai_topic_generation_attempts(contact_id, created_at DESC);
