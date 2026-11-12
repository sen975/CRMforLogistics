-- V7: call_records + call_transcript_revisions
CREATE TABLE call_records (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_anchor_point_id varchar(512) NOT NULL,
    phone_point_id      varchar(512) NOT NULL,
    direction           varchar(16) NOT NULL,
    occurred_at         timestamptz NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    created_by          varchar(128) NOT NULL,
    client_request_id   varchar(255) NOT NULL,
    note                text NOT NULL DEFAULT '',

    audio_relative_path varchar(512) NOT NULL,
    audio_original_file_name varchar(255) NOT NULL,
    audio_size_bytes    bigint NOT NULL,
    audio_sha256        varchar(64) NOT NULL,
    audio_content_type  varchar(128) NOT NULL DEFAULT 'audio/mpeg',
    audio_duration_seconds double precision NOT NULL,
    audio_object_key    varchar(255) NOT NULL,

    transcription_state varchar(32) NOT NULL DEFAULT 'queued',
    transcription_model varchar(128),
    transcription_attempts int NOT NULL DEFAULT 0,
    transcription_lease_id varchar(64),
    transcription_lease_worker_id varchar(128),
    transcription_lease_expires_at timestamptz,
    transcription_next_attempt_at timestamptz,
    transcription_result_model varchar(128),
    transcription_result_duration_seconds double precision,
    transcription_result_original_text text,
    transcription_result_segments jsonb,
    transcription_result_completed_at timestamptz,
    transcription_error_code varchar(128),
    transcription_error_message varchar(2048),
    transcription_error_retryable boolean,

    current_revision_id uuid,
    version             bigint NOT NULL DEFAULT 1,
    updated_at          timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_call_records_direction CHECK (direction IN ('inbound', 'outbound')),
    CONSTRAINT ck_call_records_transcription_state CHECK (transcription_state IN ('queued', 'processing', 'completed', 'failed'))
);

CREATE INDEX idx_call_records_anchor ON call_records (contact_anchor_point_id);
CREATE INDEX idx_call_records_phone ON call_records (phone_point_id);
CREATE INDEX idx_call_records_occurred ON call_records (occurred_at DESC, id DESC);
CREATE INDEX idx_call_records_state ON call_records (transcription_state);
CREATE UNIQUE INDEX idx_call_records_idempotency
    ON call_records (contact_anchor_point_id, client_request_id);

CREATE TABLE call_transcript_revisions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    call_record_id  uuid NOT NULL REFERENCES call_records(id),
    text            text NOT NULL,
    edited_at       timestamptz NOT NULL,
    edited_by       varchar(128) NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_call_revisions_record ON call_transcript_revisions (call_record_id);
